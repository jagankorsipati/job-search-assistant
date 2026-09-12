package com.jobsearchassistant.documents;

import static com.jobsearchassistant.documents.DocxSpikeFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

class DocxReplacementSpikeTests {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"plain", "styled", "split", "bullet", "numbered", "structures", "page-boundary"})
    void replacesOnlyIntendedParagraphAndPreservesSourcePartsPropertiesAndRuns(String fixture) throws Exception {
        Map<String, byte[]> beforeParts = parts(supportedBodies().get(fixture));
        byte[] source = DocxReplacementSpike.zip(beforeParts);
        byte[] originalSource = source.clone();
        var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
        for (String replacement : new String[] { SHORT, LONG, "  Leading & trailing <literal> text  ", "Unicode \uD83D\uDE80 replacement" }) {
            byte[] output = DocxReplacementSpike.replace(source, anchor, ORIGINAL, replacement);
            assertThat(source).isEqualTo(originalSource);
            Map<String, byte[]> afterParts = unzip(output);
            assertThat(afterParts.keySet()).isEqualTo(beforeParts.keySet());
            beforeParts.forEach((name, bytes) -> { if (!name.equals(DocxReplacementSpike.MAIN)) assertThat(afterParts.get(name)).as(name).isEqualTo(bytes); });
            var before = DocxReplacementSpike.parse(beforeParts.get(DocxReplacementSpike.MAIN));
            var after = DocxReplacementSpike.parse(afterParts.get(DocxReplacementSpike.MAIN));
            var beforeBody = DocxReplacementSpike.children(before.getElementsByTagNameNS(DocxReplacementSpike.W, "body").item(0));
            var afterBody = DocxReplacementSpike.children(after.getElementsByTagNameNS(DocxReplacementSpike.W, "body").item(0));
            assertThat(afterBody).hasSameSizeAs(beforeBody);
            for (int i = 0; i < beforeBody.size(); i++) {
                if (i != anchor.bodyChildIndex()) assertThat(afterBody.get(i).isEqualNode(beforeBody.get(i))).as("unrelated body child %s", i).isTrue();
            }
            Element target = afterBody.get(anchor.bodyChildIndex());
            assertThat(DocxReplacementSpike.paragraphText(target)).isEqualTo(replacement);
            // Restore only edited text/space attributes, then compare the entire target subtree.
            var beforeTexts = beforeBody.get(anchor.bodyChildIndex()).getElementsByTagNameNS(DocxReplacementSpike.W, "t");
            var afterTexts = target.getElementsByTagNameNS(DocxReplacementSpike.W, "t");
            assertThat(afterTexts.getLength()).isEqualTo(beforeTexts.getLength());
            for (int i = 0; i < beforeTexts.getLength(); i++) afterTexts.item(i).setTextContent(beforeTexts.item(i).getTextContent());
            assertThat(target.isEqualNode(beforeBody.get(anchor.bodyChildIndex()))).isTrue();
        }
        Path sourceFile = temporary.resolve("source.docx");
        Files.write(sourceFile, source);
        DocxReplacementSpike.replace(Files.readAllBytes(sourceFile), anchor, ORIGINAL, SHORT);
        assertThat(Files.readAllBytes(sourceFile)).isEqualTo(originalSource);
    }

    @Test
    void refusesZeroAmbiguousAndUnsupportedTargetsWithoutOutput() throws Exception {
        refuse(parts(p("Different text")), "no_match");
        refuse(parts(p(ORIGINAL) + p(ORIGINAL)), "ambiguous_match");
        refuse(parts(p(ORIGINAL) + table(ORIGINAL)), "ambiguous_match");
        refuse(parts(table(ORIGINAL)), "unsupported_target");
        refuse(parts(hyperlink(ORIGINAL)), "unsupported_target");
        refuse(parts("<w:p><w:ins w:id=\"2\">" + run(ORIGINAL, "") + "</w:ins></w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:fldSimple w:instr=\"PAGE\">" + run(ORIGINAL, "") + "</w:fldSimple></w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" + run(ORIGINAL, "") + "</w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:pPr><w:sectPr/></w:pPr>" + run(ORIGINAL, "") + "</w:p>"), "unsupported_target");
        refuse(parts("<w:p>" + run("Synthetic paragraph ", "<w:b/>") + run("for replacement.", "<w:i/>") + "</w:p>"), "mixed_run_formatting");
        refuse(parts("<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent>" + p(ORIGINAL) + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"), "ambiguous_match");
        Map<String, byte[]> header = parts(p("Other text"));
        header.put("word/header1.xml", bytes("<w:hdr xmlns:w=\"" + DocxReplacementSpike.W + "\">" + p(ORIGINAL) + "</w:hdr>"));
        refuse(header, "unsupported_target");
        header.put("word/document.xml", parts(p(ORIGINAL)).get("word/document.xml"));
        refuse(header, "ambiguous_match");
        Map<String, byte[]> footer = parts(p("Other text"));
        footer.put("word/footer1.xml", bytes("<w:ftr xmlns:w=\"" + DocxReplacementSpike.W + "\">" + p(ORIGINAL) + "</w:ftr>"));
        refuse(footer, "unsupported_target");
        refuse(parts("<w:p><w:del w:id=\"1\"><w:r><w:delText>" + ORIGINAL + "</w:delText></w:r></w:del></w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:bookmarkStart w:id=\"1\" w:name=\"anchor\"/>" + run(ORIGINAL, "") + "<w:bookmarkEnd w:id=\"1\"/></w:p>"), "unsupported_target");
        refuse(parts("<w:sdt><w:sdtContent>" + p(ORIGINAL) + "</w:sdtContent></w:sdt>"), "unsupported_target");
        refuse(parts("<w:p>" + run(ORIGINAL, "<w:vanish/>") + "</w:p>"), "unsupported_target");
        refuse(parts("<w:p>" + run(ORIGINAL, "<w:rPrChange w:id=\"1\"><w:rPr/></w:rPrChange>") + "</w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:r><w:tab/><w:t>" + ORIGINAL + "</w:t></w:r></w:p>"), "unsupported_target");
        refuse(parts("<w:p><w:r><w:br/><w:t>" + ORIGINAL + "</w:t></w:r></w:p>"), "unsupported_target");
        try (var entries = Files.list(temporary)) { assertThat(entries.toList()).isEmpty(); }
    }

    @Test
    void bindsAnchorToExactSourceAndLocationAndRejectsInvalidReplacement() {
        byte[] source = DocxReplacementSpike.zip(parts(p(ORIGINAL)));
        var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
        byte[] changed = DocxReplacementSpike.zip(parts(p(ORIGINAL) + p("Added")));
        assertThatThrownBy(() -> DocxReplacementSpike.replace(changed, anchor, ORIGINAL, SHORT)).hasMessage("source_changed");
        assertThatThrownBy(() -> DocxReplacementSpike.replace(source, new DocxReplacementSpike.Anchor(anchor.sourceSha256(), 9, anchor.paragraphSha256()), ORIGINAL, SHORT)).hasMessage("stale_anchor");
        assertThatThrownBy(() -> DocxReplacementSpike.replace(source, new DocxReplacementSpike.Anchor(anchor.sourceSha256(), 0, "wrong"), ORIGINAL, SHORT)).hasMessage("stale_anchor");
        for (String invalid : new String[] { "", "x".repeat(4001), "line\nbreak", "tab\ttext", "bad\u0000text", "\uD800" }) {
            assertThatThrownBy(() -> DocxReplacementSpike.replace(source, anchor, ORIGINAL, invalid)).hasMessage("invalid_text");
        }
    }

    @Test
    void failedReplacementCannotEmitAnExportAndLeavesInputFileUnchanged() throws Exception {
        byte[] source = DocxReplacementSpike.zip(parts(p(ORIGINAL)));
        var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
        Path originalFile = temporary.resolve("original.docx");
        Path exportFile = temporary.resolve("export.docx");
        Files.write(originalFile, source);
        assertThatThrownBy(() -> Files.write(exportFile,
                DocxReplacementSpike.replace(Files.readAllBytes(originalFile), anchor, ORIGINAL, "bad\ntext")))
                .isInstanceOf(DocxReplacementSpike.Refusal.class).hasMessage("invalid_text").hasNoCause();
        assertThat(Files.exists(exportFile)).isFalse();
        assertThat(Files.readAllBytes(originalFile)).isEqualTo(source);
    }

    @Test
    void refusesUnsafeXmlAndActiveOrMalformedPackages() throws Exception {
        Map<String, byte[]> data = parts(p(ORIGINAL));
        data.put("word/styles.xml", bytes("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///not-readable'>]><x>&secret;</x>"));
        refuse(data, "invalid_xml");
        data.put("word/styles.xml", bytes("<x xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"https://example.invalid/never-fetch\"/></x>"));
        refuse(data, "invalid_xml");
        data.put("word/styles.xml", bytes("<x>".repeat(130) + "</x>".repeat(130)));
        refuse(data, "invalid_xml");
        for (String name : new String[] { "../escape.xml", "/absolute.xml", "C:drive.xml", "word\\escape.xml", "word/%2e%2e.xml" }) {
            data = parts(p(ORIGINAL)); data.put(name, bytes("<x/>")); refuse(data, "unsafe_part_name");
        }
        for (String name : new String[] { "word/vbaProject.bin", "word/embeddings/object.bin", "word/activeX/control.xml", "_xmlsignatures/sig.xml" }) {
            data = parts(p(ORIGINAL)); data.put(name, bytes("<x/>")); refuse(data, "unsupported_package");
        }
        data = parts(p(ORIGINAL)); data.put("word/_rels/document.xml.rels", bytes("<Relationships xmlns=\"" + DocxReplacementSpike.REL + "\"><Relationship Id=\"external\" Type=\"image\" Target=\"https://example.invalid/pixel\" TargetMode=\"External\"/></Relationships>"));
        refuse(data, "external_resource");
        data = parts(p(ORIGINAL)); data.remove("_rels/.rels"); refuse(data, "unsupported_package");
        data = parts(p(ORIGINAL)); data.remove("word/header1.xml"); refuse(data, "unsupported_package");
        data = parts(p(ORIGINAL)); data.put("word/document.xml", bytes("<broken")); refuse(data, "invalid_xml");
        byte[] source = DocxReplacementSpike.zip(parts(p(ORIGINAL)));
        byte[] truncated = Arrays.copyOf(source, source.length - 22);
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(truncated, DocxReplacementSpike.sha(truncated), ORIGINAL)).hasMessage("malformed_package");
        byte[] encrypted = source.clone();
        for (int i = 0; i < encrypted.length - 10; i++) {
            if (encrypted[i] == 0x50 && encrypted[i + 1] == 0x4b && encrypted[i + 2] == 1 && encrypted[i + 3] == 2) encrypted[i + 8] |= 1;
        }
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(encrypted, DocxReplacementSpike.sha(encrypted), ORIGINAL)).hasMessage("malformed_package");
        byte[] localEncrypted = source.clone(); localEncrypted[6] |= 1;
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(localEncrypted, DocxReplacementSpike.sha(localEncrypted), ORIGINAL)).hasMessage("malformed_package");
        byte[] badCrc = source.clone();
        for (int i = 0; i < badCrc.length - 20; i++) {
            if (badCrc[i] == 0x50 && badCrc[i + 1] == 0x4b && badCrc[i + 2] == 1 && badCrc[i + 3] == 2) { badCrc[i + 16] ^= 1; break; }
        }
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(badCrc, DocxReplacementSpike.sha(badCrc), ORIGINAL)).hasMessage("malformed_package");
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(bytes("Encrypted OLE package"), DocxReplacementSpike.sha(bytes("Encrypted OLE package")), ORIGINAL)).hasMessage("malformed_package");
    }

    @Test
    void enforcesInputEntryPartExpandedXmlAndCompressionBounds() throws Exception {
        byte[] huge = new byte[DocxReplacementSpike.INPUT_LIMIT + 1];
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(huge, "unused", ORIGINAL)).hasMessage("input_limit");
        Map<String, byte[]> data = parts(p(ORIGINAL));
        for (int i = 0; i < 512; i++) data.put("extra" + i + ".bin", new byte[] {1});
        refuse(data, "entry_limit");
        data = parts(p(ORIGINAL)); data.put("large.bin", new byte[DocxReplacementSpike.PART_LIMIT + 1]); refuse(data, "part_limit");
        data = parts(p(ORIGINAL)); data.put("bomb.bin", new byte[100_000]); refuse(data, "compression_ratio");
        data = parts(p(ORIGINAL)); data.put("large.xml", bytes("<x>" + "a".repeat(DocxReplacementSpike.XML_LIMIT) + "</x>"));
        byte[] stored = storedZip(data);
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(stored, DocxReplacementSpike.sha(stored), ORIGINAL)).hasMessage("xml_limit");
        data = parts(p(ORIGINAL));
        byte[] binary = new byte[7 * 1024 * 1024]; Random random = new Random(42);
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) random.nextInt(2);
        for (int i = 0; i < 4; i++) data.put("expanded" + i + ".bin", binary);
        refuse(data, "expanded_limit");
    }

    @Test
    void optionallyMaterializesOnlySyntheticBeforeAfterRenderFixtures() throws Exception {
        if (!Boolean.getBoolean("docx.spike.fixtures")) return;
        Path output = Path.of("target", "docx-spike");
        Files.createDirectories(output);
        for (var fixture : supportedBodies().entrySet()) {
            byte[] source = DocxReplacementSpike.zip(parts(fixture.getValue()));
            var anchor = DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL);
            Files.write(output.resolve(fixture.getKey() + "-before.docx"), source);
            Files.write(output.resolve(fixture.getKey() + "-short.docx"), DocxReplacementSpike.replace(source, anchor, ORIGINAL, SHORT));
            Files.write(output.resolve(fixture.getKey() + "-long.docx"), DocxReplacementSpike.replace(source, anchor, ORIGINAL, LONG));
        }
    }

    private void refuse(Map<String, byte[]> parts, String code) {
        byte[] source = DocxReplacementSpike.zip(parts); byte[] saved = source.clone();
        assertThatThrownBy(() -> DocxReplacementSpike.resolve(source, DocxReplacementSpike.sha(source), ORIGINAL))
                .isInstanceOf(DocxReplacementSpike.Refusal.class).hasMessage(code).hasNoCause();
        assertThat(source).isEqualTo(saved);
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
    private static byte[] storedZip(Map<String, byte[]> parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var part : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(part.getKey()); entry.setMethod(ZipEntry.STORED);
                entry.setSize(part.getValue().length); CRC32 crc = new CRC32(); crc.update(part.getValue()); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(part.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
