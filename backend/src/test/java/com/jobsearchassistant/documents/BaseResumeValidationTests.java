package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaseResumeValidationTests {
    @TempDir Path tempDir;

    @Test
    void normalizesFilenamesAndRejectsUnsafeNames() {
        assertThat(BaseResumeFilename.normalize("C:\\fakepath\\Resume.pdf")).isEqualTo("Resume.pdf");
        assertThat(BaseResumeFilename.normalize("../Resume.docx")).isEqualTo("Resume.docx");
        assertThatThrownBy(() -> BaseResumeFilename.normalize(".")).isInstanceOf(BaseResumeValidationException.class);
        assertThatThrownBy(() -> BaseResumeFilename.normalize("resume\u0000.pdf"))
                .isInstanceOf(BaseResumeValidationException.class);
        assertThat(BaseResumeFilename.normalize("folder/resume.pdf")).isEqualTo("resume.pdf");
    }

    @Test
    void validatesPdfSignatureAndFilenameMatch() throws Exception {
        Path pdf = write("resume.pdf", pdfBytes());
        assertThat(BaseResumeValidator.validate(pdf, "resume.pdf", Files.size(pdf))).isEqualTo(BaseResumeValidator.PDF);
        assertThatThrownBy(() -> BaseResumeValidator.validate(pdf, "resume.docx", Files.size(pdf)))
                .isInstanceOf(BaseResumeValidationException.class);
        assertThatThrownBy(() -> BaseResumeValidator.validate(write("bad.pdf", "plain text".getBytes()), "bad.pdf", 10))
                .isInstanceOf(BaseResumeValidationException.class);
    }

    @Test
    void validatesDocxStructureAndRejectsGenericZipAndTraversal() throws Exception {
        Path docx = write("resume.docx", docxBytes("docProps/core.xml", "<cp/>".getBytes()));
        assertThat(BaseResumeValidator.validate(docx, "resume.docx", Files.size(docx))).isEqualTo(BaseResumeValidator.DOCX);
        Path genericZip = write("generic.docx", genericZipBytes());
        assertThatThrownBy(() -> BaseResumeValidator.validate(genericZip, "generic.docx", Files.size(genericZip)))
                .isInstanceOf(BaseResumeValidationException.class);
        Path traversal = write("traversal.docx", docxBytes("../evil.txt", "x".getBytes()));
        assertThatThrownBy(() -> BaseResumeValidator.validate(traversal, "traversal.docx", Files.size(traversal)))
                .isInstanceOf(BaseResumeValidationException.class);
    }

    @Test
    void storageStagesChecksumsPublishesAndContainsPaths() throws Exception {
        LocalBaseResumeStorage storage = new LocalBaseResumeStorage(new BaseResumeStorageProperties(tempDir.resolve("storage")));
        StoredBaseResume staged = storage.stage(new ByteArrayInputStream(pdfBytes()), "resume.pdf");
        assertThat(staged.input().byteSize()).isEqualTo(pdfBytes().length);
        assertThat(staged.input().sha256Checksum()).matches("[0-9a-f]{64}");
        assertThat(Files.exists(staged.stagedPath())).isTrue();
        storage.publish(staged);
        BaseResumeDownload download = storage.open(staged.storageKey());
        assertThat(download.stream().readAllBytes()).isEqualTo(pdfBytes());
        download.stream().close();
        storage.deleteIfExists(staged.storageKey());
    }

    @Test
    void storageRejectsOversizedInputAndCleansStagedFile() {
        LocalBaseResumeStorage storage = new LocalBaseResumeStorage(new BaseResumeStorageProperties(tempDir.resolve("storage")));
        byte[] oversized = new byte[(int) BaseResumeValidator.MAX_BYTES + 1];
        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(oversized), "resume.pdf"))
                .isInstanceOf(BaseResumeValidationException.class);
    }

    private Path write(String name, byte[] bytes) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    static byte[] docxBytes(String extraEntry, byte[] extraBytes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(extraEntry));
            zip.write(extraBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static byte[] genericZipBytes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("file.txt"));
            zip.write("hello".getBytes());
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
