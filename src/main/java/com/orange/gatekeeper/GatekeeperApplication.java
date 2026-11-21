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
import java.util.stream.Stream;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class GatekeeperApplication {

    @Value("${gatekeeper.central.dir:./central_staging_dir}")
    private String centralDirPath;

    @Value("${gatekeeper.inbound.dir:./inbound}")
    private String inboundDirPath;

    @Value("${gatekeeper.domains.file:./domains.txt}")
    private String domainsFilePath;

    @Value("${gatekeeper.target.dist.dir:./distributed_nodes_target}")
    private String targetDistDirPath;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<DeploymentRecord> registry = new CopyOnWriteArrayList<>();
    private Path registryFile;
    private Path inboundDir;

    public static void main(String[] args) {
        SpringApplication.run(GatekeeperApplication.class, args);
    }

    @PostConstruct
    public void init() throws IOException {
        Path baseDir = Paths.get(centralDirPath).toAbsolutePath().normalize();
        Files.createDirectories(baseDir.resolve("staging"));
        Files.createDirectories(baseDir.resolve("registry"));
        Files.createDirectories(baseDir.resolve("logs"));

        inboundDir = Paths.get(inboundDirPath).toAbsolutePath().normalize();
        Files.createDirectories(inboundDir);

        Files.createDirectories(Paths.get(targetDistDirPath).toAbsolutePath().normalize());

        Path domPath = Paths.get(domainsFilePath).toAbsolutePath().normalize();
        if (!Files.exists(domPath)) {
            List<String> defaults = Arrays.asList(
                "# Orange Egypt Integration Domains (One domain per line)",
                "BucketOnline",
                "CampaignHousekeep",
                "CampaignNearOnline",
                "CampaignOnline",
                "Critical",
                "CustomerOfferOnline",
                "DSLHouseKeep",
                "DSLNearOnline",
                "DSLOnline",
                "EVM",
                "HouseKeep",
                "HouseKeepOld",
                "MNP",
                "NearOnline",
                "OldNearOnline",
                "OldOnline",
                "ProductOnline"
            );
            Files.write(domPath, defaults, StandardCharsets.UTF_8);
        }

        registryFile = baseDir.resolve("registry").resolve("deployments.json");
        if (Files.exists(registryFile)) {
            try {
                List<DeploymentRecord> loaded = mapper.readValue(
                    registryFile.toFile(),
                    new TypeReference<List<DeploymentRecord>>() {}
                );
                registry.addAll(loaded);
            } catch (Exception e) {
                System.err.println("Starting with fresh registry: " + e.getMessage());
            }
        }
    }

    // 1. Read domains dynamically from ./domains.txt
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

    // 1b. Update ./domains.txt directly from the UI editor
    @PutMapping("/domains")
    public synchronized ResponseEntity<?> updateDomains(@RequestBody Map<String, String> body) throws IOException {
        String content = body.getOrDefault("content", "");
        Path domPath = Paths.get(domainsFilePath).toAbsolutePath().normalize();
        Files.writeString(domPath, content, StandardCharsets.UTF_8);
        return ResponseEntity.ok(getDomains());
    }

    // 2. Get all deployments (Ops View imports directly from Dev View submissions)
    @GetMapping("/deployments")
    public List<DeploymentRecord> getDeployments() {
        List<DeploymentRecord> sorted = new ArrayList<>(registry);
        Collections.reverse(sorted);
        return sorted;
    }

    // 3. List all generated .txt files in ./inbound
    @GetMapping("/inbound")
    public List> getInboundFiles() throws IOException {
        if (!Files.exists(inboundDir)) {
            return Collections.emptyList();
        }
        List> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(inboundDir)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .collect(Collectors.toList());
            for (Path p : files) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("fileName", p.getFileName().toString());
                item.put("fullPath", "./inbound/" + p.getFileName().toString());
                item.put("content", Files.readString(p, StandardCharsets.UTF_8));
                result.add(item);
            }
        }
        return result;
    }

    // 4. Submit new deployment from Dev View
    @PostMapping("/deployments")
    public synchronized ResponseEntity<?> submitDeployment(
            @RequestParam("developer") String developer,
            @RequestParam("mail") String mail,
            @RequestParam(value = "ticketId", required = false, defaultValue = "") String ticketId,
            @RequestParam("domain") String domain,
            @RequestParam("businessService") String businessService,
            @RequestParam("serviceName") String serviceName,
            @RequestParam("packageType") String packageType,
            @RequestParam(value = "createScheduler", defaultValue = "false") boolean createScheduler,
            @RequestParam(value = "createConnection", defaultValue = "false") boolean createConnection,
            @RequestParam(value = "createQueue", defaultValue = "false") boolean createQueue,
            @RequestParam(value = "description", required = false, defaultValue = "") String description,
            @RequestParam(value = "instructions", required = false, defaultValue = "") String instructions,
            @RequestParam(value = "rollbackPlan", required = false, defaultValue = "") String rollbackPlan,
            @RequestParam("artifact") MultipartFile artifact) throws Exception {

        if (artifact.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Artifact file is required"));
        }

        // Optional Ticket ID: Auto-generate UUID v4 if left empty
        boolean isAutoUuid = (ticketId == null || ticketId.trim().isEmpty());
        String finalTicketId = isAutoUuid
                ? UUID.randomUUID().toString().toLowerCase()
                : ticketId.trim();

        String datePart = LocalDate.now().toString();
        String nowTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String id = "DEP-" + (1000 + registry.size() + 1);

        String safeTicket = sanitize(finalTicketId);
        String safeService = sanitize(serviceName);
        String safeDomain = sanitize(domain);

        String folderName = datePart + "_" + safeDomain + "_" + safeTicket + "_" + safeService + "_" + id;
        Path releaseDir = Paths.get(centralDirPath).toAbsolutePath().normalize()
                .resolve("staging").resolve(folderName);
        Path artifactDir = releaseDir.resolve("artifacts");
        Files.createDirectories(artifactDir);

        String origName = Paths.get(Objects.requireNonNull(artifact.getOriginalFilename())).getFileName().toString();
        Path savedFile = artifactDir.resolve(origName);
        try (InputStream in = artifact.getInputStream()) {
            Files.copy(in, savedFile, StandardCopyOption.REPLACE_EXISTING);
        }

        String sha256 = computeSha256(savedFile);
        String componentType = mapPackageToComponent(packageType.trim());

        StagedFile initialFile = new StagedFile();
        initialFile.name = origName;
        initialFile.size = artifact.getSize();
        initialFile.sha256 = sha256;
        initialFile.type = "ORIGINAL";
        initialFile.packageType = packageType.trim();
        initialFile.uploadedAt = nowTimestamp;
        initialFile.notes = "Initial handover submission";

        DeploymentRecord record = new DeploymentRecord();
        record.id = id;
        record.timestamp = nowTimestamp;
        record.developer = developer.trim();
        record.mail = mail.trim();
        record.ticketId = finalTicketId;
        record.isAutoUuid = isAutoUuid;
        record.domain = domain.trim();
        record.businessService = businessService.trim();
        record.serviceName = serviceName.trim();
        record.packageType = packageType.trim();
        record.componentType = componentType;
        record.createScheduler = createScheduler;
        record.createConnection = createConnection;
        record.createQueue = createQueue;
        record.description = description == null || description.trim().isEmpty() ? "N/A (None provided)" : description.trim();
        record.instructions = instructions == null || instructions.trim().isEmpty() ? "N/A (None provided)" : instructions.trim();
        record.rollbackPlan = rollbackPlan == null || rollbackPlan.trim().isEmpty() ? "N/A (None provided)" : rollbackPlan.trim();
        record.fileName = origName;
        record.fileSize = artifact.getSize();
        record.sha256 = sha256;
        record.stagingPath = releaseDir.toString();
        record.inboundFileName = datePart + "_" + safeTicket + "_" + safeDomain + "_" + safeService + ".txt";
        record.status = "Submitted";
        record.files.add(initialFile);
        record.distributionLog = "[" + nowTimestamp + "] Staged original file (" + origName + ") and saved ./inbound/" + record.inboundFileName;

        writeManifestAndInboundTxt(record);

        registry.add(record);
        saveRegistry();

        return ResponseEntity.ok(record);
    }

    // 5. UPDATE DEPLOYMENT: Add new file with "_updated" appended & keep old file(s) in same directory
    @PostMapping("/deployments/{id}/update")
    public synchronized ResponseEntity<?> updateDeploymentFile(
            @PathVariable String id,
            @RequestParam("packageType") String packageType,
            @RequestParam(value = "updateNotes", required = false, defaultValue = "") String updateNotes,
            @RequestParam("artifact") MultipartFile artifact) throws Exception {

        if (artifact.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Updated artifact file is required"));
        }

        for (DeploymentRecord rec : registry) {
            if (rec.id.equals(id)) {
                Path releaseDir = Paths.get(rec.stagingPath);
                Path artifactDir = releaseDir.resolve("artifacts");
                Files.createDirectories(artifactDir);

                String rawName = Paths.get(Objects.requireNonNull(artifact.getOriginalFilename())).getFileName().toString();
                String updatedFileName = buildUpdatedFileName(artifactDir, rawName);

                Path savedFile = artifactDir.resolve(updatedFileName);
                try (InputStream in = artifact.getInputStream()) {
                    Files.copy(in, savedFile, StandardCopyOption.REPLACE_EXISTING);
                }

                String newSha256 = computeSha256(savedFile);
                String nowTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                StagedFile updatedEntry = new StagedFile();
                updatedEntry.name = updatedFileName;
                updatedEntry.size = artifact.getSize();
                updatedEntry.sha256 = newSha256;
                updatedEntry.type = "UPDATED";
                updatedEntry.packageType = packageType.trim();
                updatedEntry.uploadedAt = nowTimestamp;
                updatedEntry.notes = (updateNotes == null || updateNotes.trim().isEmpty())
                        ? "Updated artifact appended to same directory"
                        : updateNotes.trim();

                rec.files.add(updatedEntry);
                rec.fileName = updatedFileName;
                rec.fileSize = artifact.getSize();
                rec.sha256 = newSha256;
                rec.packageType = packageType.trim();
                rec.componentType = mapPackageToComponent(packageType.trim());
                rec.timestamp = nowTimestamp + " (UPDATED)";
                rec.distributionLog += "\n[" + nowTimestamp + "] UPDATED ARTIFACT: Added '" + updatedFileName
                        + "' (" + packageType.trim() + ") to same directory. Previous file versions preserved.";

                writeManifestAndInboundTxt(rec);
                saveRegistry();
                return ResponseEntity.ok(rec);
            }
        }
        return ResponseEntity.notFound().build();
    }

    // 6. Update status from Ops View (Submitted, Deployed, Rolled Back)
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
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                rec.distributionLog += "\n[" + now + "] Status updated to '" + newStatus + "' by Ops Control Center.";
                writeManifestAndInboundTxt(rec);
                saveRegistry();
                return ResponseEntity.ok(rec);
            }
        }
        return ResponseEntity.notFound().build();
    }

    // 7. Distribute all staged files in directory to target domain folder
    @PostMapping("/deployments/{id}/distribute")
    public synchronized ResponseEntity<?> distributeDeployment(@PathVariable String id) throws Exception {
        for (DeploymentRecord rec : registry) {
            if (rec.id.equals(id)) {
                Path artifactDir = Paths.get(rec.stagingPath).resolve("artifacts");
                Path targetDomainDir = Paths.get(targetDistDirPath).toAbsolutePath().normalize()
                        .resolve(sanitize(rec.domain));
                Files.createDirectories(targetDomainDir);

                StringBuilder distSummary = new StringBuilder();
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                for (StagedFile sf : rec.files) {
                    Path src = artifactDir.resolve(sf.name);
                    if (Files.exists(src)) {
                        Path dst = targetDomainDir.resolve(sf.name);
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        String verifyHash = computeSha256(dst);
                        boolean ok = verifyHash.equalsIgnoreCase(sf.sha256);
                        distSummary.append("\n[").append(now).append("] Distributed ")
                                .append(sf.name).append(" -> ").append(dst)
                                .append(" (SHA-256 Verified: ").append(ok).append(")");
                    }
                }
                rec.distributionLog += distSummary.toString();
                writeManifestAndInboundTxt(rec);
                saveRegistry();
                return ResponseEntity.ok(rec);
            }
        }
        return ResponseEntity.notFound().build();
    }

    private String mapPackageToComponent(String pkgType) {
        if ("Code".equalsIgnoreCase(pkgType)) {
            return "webMethods IS Package";
        } else if ("Code & Configurations".equalsIgnoreCase(pkgType)) {
            return "webMethods IS Package & Configurations";
        }
        return "Configurations";
    }

    // Appends "_updated" before the file extension while never overwriting existing files in the same dir
    private String buildUpdatedFileName(Path artifactDir, String originalFilename) {
        int dotIdx = originalFilename.lastIndexOf('.');
        String base = (dotIdx > 0) ? originalFilename.substring(0, dotIdx) : originalFilename;
        String ext = (dotIdx > 0) ? originalFilename.substring(dotIdx) : "";

        base = base.replaceAll("_updated(_\\d+)?$", "");
        String candidate = base + "_updated" + ext;
        int counter = 2;
        while (Files.exists(artifactDir.resolve(candidate))) {
            candidate = base + "_updated_" + counter + ext;
            counter++;
        }
        return candidate;
    }

    private void writeManifestAndInboundTxt(DeploymentRecord rec) throws IOException {
        Path releaseDir = Paths.get(rec.stagingPath);
        Files.createDirectories(releaseDir);
        mapper.writeValue(releaseDir.resolve("manifest.json").toFile(), rec);

        StringBuilder filesHistory = new StringBuilder();
        for (int i = 0; i < rec.files.size(); i++) {
            StagedFile f = rec.files.get(i);
            filesHistory.append("  ").append(i + 1).append(". [").append(f.type).append("] ")
                    .append(f.name)
                    .append(" | PkgType: ").append(f.packageType)
                    .append(" | SHA-256: ").append(f.sha256)
                    .append(" | Time: ").append(f.uploadedAt)
                    .append(" | Notes: ").append(f.notes).append("\n");
        }

        String txtContent = "========================================================================\n"
                + "ORANGE EGYPT — INBOUND DEPLOYMENT HANDOVER MANIFEST\n"
                + "========================================================================\n"
                + "INBOUND FILE PATH   : ./inbound/" + rec.inboundFileName + "\n"
                + "DEPLOYMENT ID       : " + rec.id + "\n"
                + "SUBMISSION TIME     : " + rec.timestamp + "\n"
                + "CURRENT OPS STATUS  : " + rec.status + "\n"
                + "------------------------------------------------------------------------\n"
                + "1. DEVELOPER & IDENTIFICATION\n"
                + "------------------------------------------------------------------------\n"
                + "Developer Name/CUID : " + rec.developer + "\n"
                + "Developer Mail      : " + rec.mail + "\n"
                + "Ticket / CR ID      : " + rec.ticketId + (rec.isAutoUuid ? " (Auto-Generated UUID)" : "") + "\n"
                + "------------------------------------------------------------------------\n"
                + "2. TARGET ARCHITECTURE & CLASSIFICATION\n"
                + "------------------------------------------------------------------------\n"
                + "Domain              : " + rec.domain + "\n"
                + "Business Service    : " + rec.businessService + "\n"
                + "Service / Package   : " + rec.serviceName + "\n"
                + "Dev Package Type    : " + rec.packageType + "\n"
                + "Ops Component Type  : " + rec.componentType + "\n"
                + "------------------------------------------------------------------------\n"
                + "3. INFRASTRUCTURE & PROVISIONING CHECKS\n"
                + "------------------------------------------------------------------------\n"
                + "Create Scheduler    : [" + (rec.createScheduler ? "X" : " ") + "] " + (rec.createScheduler ? "YES" : "NO") + "\n"
                + "Create Connection   : [" + (rec.createConnection ? "X" : " ") + "] " + (rec.createConnection ? "YES" : "NO") + "\n"
                + "Create Queue        : [" + (rec.createQueue ? "X" : " ") + "] " + (rec.createQueue ? "YES" : "NO") + "\n"
                + "------------------------------------------------------------------------\n"
                + "4. STAGED ARTIFACT FILES (PRESERVED IN SAME DIRECTORY)\n"
                + "------------------------------------------------------------------------\n"
                + "Staging Directory   : " + rec.stagingPath + "/artifacts/\n"
                + "Active Latest File  : " + rec.fileName + "\n"
                + "Latest SHA-256      : " + rec.sha256 + "\n"
                + "Directory Files (" + rec.files.size() + ") :\n"
                + filesHistory
                + "------------------------------------------------------------------------\n"
                + "5. DESCRIPTION (OPTIONAL)\n"
                + "------------------------------------------------------------------------\n"
                + rec.description + "\n"
                + "------------------------------------------------------------------------\n"
                + "6. DEPLOYMENT INSTRUCTIONS & PRE-REQUISITES (OPTIONAL)\n"
                + "------------------------------------------------------------------------\n"
                + rec.instructions + "\n"
                + "------------------------------------------------------------------------\n"
                + "7. ROLLBACK PLAN (OPTIONAL)\n"
                + "------------------------------------------------------------------------\n"
                + rec.rollbackPlan + "\n"
                + "========================================================================\n";

        Files.writeString(releaseDir.resolve("release_notes.txt"), txtContent, StandardCharsets.UTF_8);
        Files.writeString(inboundDir.resolve(rec.inboundFileName), txtContent, StandardCharsets.UTF_8);
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

    public static class StagedFile {
        public String name;
        public long size;
        public String sha256;
        public String type; // ORIGINAL or UPDATED
        public String packageType;
        public String uploadedAt;
        public String notes;
    }

    public static class DeploymentRecord {
        public String id;
        public String timestamp;
        public String developer;
        public String mail;
        public String ticketId;
        public boolean isAutoUuid;
        public String domain;
        public String businessService;
        public String serviceName;
        public String packageType;
        public String componentType;
        public boolean createScheduler;
        public boolean createConnection;
        public boolean createQueue;
        public String description;
        public String instructions;
        public String rollbackPlan;
        public String fileName;
        public long fileSize;
        public String sha256;
        public String stagingPath;
        public String inboundFileName;
        public String status;
        public String distributionLog;
        public List<StagedFile> files = new ArrayList<>();
    }
}