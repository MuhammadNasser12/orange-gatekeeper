package com.orange.gatekeeper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class GatekeeperApplication {

    @Value("${gatekeeper.central.dir:./central_staging_dir}")
    private String centralDirPath;

    @Value("${gatekeeper.domains.file:./domains.txt}")
    private String domainsFilePath;

    @Value("${gatekeeper.target.dist.dir:./distributed_nodes_target}")
    private String targetDistDirPath;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<DeploymentRecord> registry = new CopyOnWriteArrayList<>();
    private Path registryFile;

    public static void main(String[] args) {
        SpringApplication.run(GatekeeperApplication.class, args);
    }

    @PostConstruct
    public void init() throws IOException {
        Path baseDir = Paths.get(centralDirPath).toAbsolutePath().normalize();
        Files.createDirectories(baseDir.resolve("staging"));
        Files.createDirectories(baseDir.resolve("registry"));
        Files.createDirectories(baseDir.resolve("logs"));
        Files.createDirectories(Paths.get(targetDistDirPath).toAbsolutePath().normalize());

        // Ensure domains.txt exists in the working directory
        Path domPath = Paths.get(domainsFilePath).toAbsolutePath().normalize();
        if (!Files.exists(domPath)) {
            List<String> defaults = Arrays.asList(
                "# Orange Egypt Integration Domains",
                "Billing-BSCS",
                "CRM-Siebel",
                "Payment-Gateway",
                "Digital-MyOrange",
                "Order-Management"
            );
            Files.write(domPath, defaults, StandardCharsets.UTF_8);
        }

        // Load existing deployments from JSON registry
        registryFile = baseDir.resolve("registry").resolve("deployments.json");
        if (Files.exists(registryFile)) {
            List<DeploymentRecord> loaded = mapper.readValue(
                registryFile.toFile(),
                new TypeReference<List<DeploymentRecord>>() {}
            );
            registry.addAll(loaded);
        }
    }

    // 1. Read domains dynamically from ./domains.txt on every request
    @GetMapping("/domains")
    public List<String> getDomains() throws IOException {
        Path domPath = Paths.get(domainsFilePath);
        if (!Files.exists(domPath)) {
            return Collections.emptyList();
        }
        return Files.readAllLines(domPath, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toList());
    }

    // 2. Get all deployments (imported directly from Dev submissions)
    @GetMapping("/deployments")
    public List<DeploymentRecord> getDeployments() {
        List<DeploymentRecord> sorted = new ArrayList<>(registry);
        Collections.reverse(sorted);
        return sorted;
    }

    // 3. Submit a new deployment from Dev View
    @PostMapping("/deployments")
    public synchronized ResponseEntity<?> submitDeployment(
            @RequestParam("developer") String developer,
            @RequestParam("ticketId") String ticketId,
            @RequestParam("domain") String domain,
            @RequestParam("serviceName") String serviceName,
            @RequestParam("packageType") String packageType,
            @RequestParam("instructions") String instructions,
            @RequestParam("rollbackPlan") String rollbackPlan,
            @RequestParam("artifact") MultipartFile artifact) throws Exception {

        if (artifact.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Artifact file is required"));
        }

        String datePart = LocalDate.now().toString();
        String safeTicket = sanitize(ticketId);
        String safeService = sanitize(serviceName);
        String safeDomain = sanitize(domain);
        String id = "DEP-" + (1000 + registry.size() + 1);

        String folderName = datePart + "_" + safeDomain + "_" + safeTicket + "_" + safeService + "_" + id;
        Path releaseDir = Paths.get(centralDirPath).toAbsolutePath().normalize()
                .resolve("staging").resolve(folderName);
        Path artifactDir = releaseDir.resolve("artifacts");
        Files.createDirectories(artifactDir);

        String fileName = Paths.get(Objects.requireNonNull(artifact.getOriginalFilename())).getFileName().toString();
        Path savedFile = artifactDir.resolve(fileName);
        try (InputStream in = artifact.getInputStream()) {
            Files.copy(in, savedFile, StandardCopyOption.REPLACE_EXISTING);
        }

        String sha256 = computeSha256(savedFile);

        // Map Dev View Package Type ("Code" / "Configurations") -> Ops View Component Type
        String componentType = "Code".equalsIgnoreCase(packageType.trim())
                ? "webMethods IS Package"
                : "Configurations";

        DeploymentRecord record = new DeploymentRecord();
        record.id = id;
        record.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        record.developer = developer.trim();
        record.ticketId = ticketId.trim().toUpperCase();
        record.domain = domain.trim();
        record.serviceName = serviceName.trim();
        record.packageType = packageType.trim();
        record.componentType = componentType;
        record.fileName = fileName;
        record.fileSize = artifact.getSize();
        record.sha256 = sha256;
        record.stagingPath = releaseDir.toString();
        record.instructions = instructions.trim();
        record.rollbackPlan = rollbackPlan.trim();
        record.status = "Submitted";
        record.distributionLog = "Staged in central directory. Awaiting Ops verification.";

        // Write manifest.json and release_notes.txt inside the release directory
        mapper.writeValue(releaseDir.resolve("manifest.json").toFile(), record);

        String notesContent = "========================================================\n"
                + "ORANGE EGYPT DEPLOYMENT HANDOVER MANIFEST\n"
                + "========================================================\n"
                + "Deployment ID  : " + record.id + "\n"
                + "Timestamp      : " + record.timestamp + "\n"
                + "Developer      : " + record.developer + "\n"
                + "Ticket ID      : " + record.ticketId + "\n"
                + "Domain         : " + record.domain + "\n"
                + "Service Name   : " + record.serviceName + "\n"
                + "Package Type   : " + record.packageType + " (" + record.componentType + ")\n"
                + "Artifact File  : " + record.fileName + "\n"
                + "SHA-256 Hash   : " + record.sha256 + "\n\n"
                + "--- DEPLOYMENT INSTRUCTIONS & PRE-REQUISITES ---\n"
                + record.instructions + "\n\n"
                + "--- ROLLBACK PLAN ---\n"
                + record.rollbackPlan + "\n";

        Files.writeString(releaseDir.resolve("release_notes.txt"), notesContent, StandardCharsets.UTF_8);

        registry.add(record);
        saveRegistry();

        return ResponseEntity.ok(record);
    }

    // 4. Update Deployment Status from Ops View (Submitted, Deployed, Rolled Back)
    @PatchMapping("/deployments/{id}/status")
    public synchronized ResponseEntity<?> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> payload) throws IOException {

        String newStatus = payload.get("status");
        if (!Arrays.asList("Submitted", "Deployed", "Rolled Back").contains(newStatus)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status value"));
        }

        for (DeploymentRecord rec : registry) {
            if (rec.id.equals(id)) {
                rec.status = newStatus;
                saveRegistry();
                mapper.writeValue(Paths.get(rec.stagingPath).resolve("manifest.json").toFile(), rec);
                return ResponseEntity.ok(rec);
            }
        }
        return ResponseEntity.notFound().build();
    }

    // 5. Distribute Artifact from Central Directory to Target Domain Directory / Nodes
    @PostMapping("/deployments/{id}/distribute")
    public synchronized ResponseEntity<?> distributeDeployment(@PathVariable String id) throws Exception {
        for (DeploymentRecord rec : registry) {
            if (rec.id.equals(id)) {
                Path sourceFile = Paths.get(rec.stagingPath).resolve("artifacts").resolve(rec.fileName);
                Path targetDomainDir = Paths.get(targetDistDirPath).toAbsolutePath().normalize()
                        .resolve(sanitize(rec.domain));
                Files.createDirectories(targetDomainDir);

                Path targetFile = targetDomainDir.resolve(rec.fileName);
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

                String targetHash = computeSha256(targetFile);
                boolean hashVerified = targetHash.equalsIgnoreCase(rec.sha256);

                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                rec.distributionLog = "[" + now + "] Distributed from Central Staging to "
                        + targetFile + " | SHA-256 Verified: " + hashVerified;

                saveRegistry();
                return ResponseEntity.ok(rec);
            }
        }
        return ResponseEntity.notFound().build();
    }

    private void saveRegistry() throws IOException {
        mapper.writeValue(registryFile.toFile(), registry);
    }

    private String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class DeploymentRecord {
        public String id;
        public String timestamp;
        public String developer;
        public String ticketId;
        public String domain;
        public String serviceName;
        public String packageType;
        public String componentType;
        public String fileName;
        public long fileSize;
        public String sha256;
        public String stagingPath;
        public String instructions;
        public String rollbackPlan;
        public String status;
        public String distributionLog;
    }
}