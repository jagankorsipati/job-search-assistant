package com.jobsearchassistant.documents;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reproducible, synthetic OOXML; contains no uploaded resume or candidate information. */
final class DocxSpikeFixtures {
    static final String ORIGINAL = "Synthetic paragraph for replacement.";
    static final String SHORT = "Short synthetic replacement.";
    static final String LONG = "Longer synthetic replacement describing a fictional project and its fictional results. ".repeat(12).strip();
    static final String SECTION = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"header\"/>"
            + "<w:footerReference w:type=\"default\" r:id=\"footer\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/>"
            + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\"/></w:sectPr>";

    static Map<String, byte[]> parts(String body) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", bytes("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                  <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
                  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
                  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
                </Types>
                """));
        parts.put("_rels/.rels", bytes("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="main" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """));
        parts.put("word/document.xml", bytes("<w:document xmlns:w=\"" + DocxReplacementSpike.W
                + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>" + body + SECTION + "</w:body></w:document>"));
        parts.put("word/_rels/document.xml.rels", bytes("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                  <Relationship Id="numbers" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
                  <Relationship Id="header" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
                  <Relationship Id="footer" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
                  <Relationship Id="link" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.invalid/never-fetch" TargetMode="External"/>
                </Relationships>
                """));
        parts.put("word/styles.xml", bytes("""
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
                  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="120"/></w:pPr></w:style>
                  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
                </w:styles>
                """));
        parts.put("word/numbering.xml", bytes("""
                <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#8226;"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
                  <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
                  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
                </w:numbering>
                """));
        parts.put("word/header1.xml", bytes("<w:hdr xmlns:w=\"" + DocxReplacementSpike.W + "\">" + p("Synthetic header") + "</w:hdr>"));
        parts.put("word/footer1.xml", bytes("<w:ftr xmlns:w=\"" + DocxReplacementSpike.W + "\">" + p("Synthetic footer") + "</w:ftr>"));
        return parts;
    }
    static String p(String text) { return "<w:p>" + run(text, "") + "</w:p>"; }
    static String run(String text, String properties) {
        return "<w:r>" + (properties.isEmpty() ? "" : "<w:rPr>" + properties + "</w:rPr>")
                + "<w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r>";
    }
    static String numbered(int id, String text) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"" + id + "\"/></w:numPr></w:pPr>" + run(text, "") + "</w:p>";
    }
    static String table(String text) { return "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"9360\" w:type=\"dxa\"/></w:tcPr>" + p(text) + "</w:tc></w:tr></w:tbl>"; }
    static String hyperlink(String text) { return "<w:p><w:hyperlink r:id=\"link\">" + run(text, "<w:color w:val=\"0000FF\"/><w:u w:val=\"single\"/>") + "</w:hyperlink></w:p>"; }
    static String context() {
        return numbered(1, "Untouched bullet") + numbered(2, "Untouched numbered item") + table("Untouched table")
                + hyperlink("Untouched hyperlink")
                + "<w:p><w:ins w:id=\"1\" w:author=\"Synthetic\" w:date=\"2026-01-01T00:00:00Z\">" + run("Untouched tracked text", "") + "</w:ins></w:p>"
                + "<w:p><w:fldSimple w:instr=\"PAGE\">" + run("1", "") + "</w:fldSimple></w:p>"
                + "<w:p><w:r><w:pict><v:shape id=\"synthetic-box\" style=\"width:120pt;height:24pt\"><v:textbox><w:txbxContent>" + p("Untouched text box") + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
                + "<w:p><w:pPr><w:sectPr><w:type w:val=\"nextPage\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:pPr>" + run("Untouched section boundary", "") + "</w:p>";
    }
    static Map<String, String> supportedBodies() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("plain", p(ORIGINAL));
        bodies.put("styled", "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>" + run(ORIGINAL, "<w:b/><w:color w:val=\"245060\"/>") + "</w:p>");
        bodies.put("split", "<w:p>" + run("Synthetic paragraph ", "<w:b/>") + run("for replacement.", "<w:b/>") + "</w:p>");
        bodies.put("bullet", numbered(1, ORIGINAL));
        bodies.put("numbered", numbered(2, ORIGINAL));
        bodies.put("structures", p(ORIGINAL) + context());
        StringBuilder boundary = new StringBuilder();
        for (int i = 0; i < 32; i++) boundary.append(p("Synthetic boundary filler line " + i));
        bodies.put("page-boundary", boundary + p(ORIGINAL) + p("Trailing boundary sentinel"));
        return bodies;
    }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
