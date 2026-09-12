package com.jobsearchassistant.documents;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Synthetic-fixture experiment only: no application service, storage mutation, or export endpoint. */
final class DocxReplacementSpike {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
    static final String MAIN = "word/document.xml";
    static final int INPUT_LIMIT = 5 * 1024 * 1024;
    static final int ENTRY_LIMIT = 512;
    static final int PART_LIMIT = 8 * 1024 * 1024;
    static final int EXPANDED_LIMIT = 25 * 1024 * 1024;
    static final int XML_LIMIT = 2 * 1024 * 1024;
    static final int RATIO_LIMIT = 100;

    record Anchor(String sourceSha256, int bodyChildIndex, String paragraphSha256) { }
    static final class Refusal extends RuntimeException {
        Refusal(String code) { super(code); }
    }
    private record PackageData(Map<String, byte[]> parts, Document document) { }
    private record Target(Element paragraph, int bodyChildIndex) { }

    static Anchor resolve(byte[] source, String expectedSha256, String original) {
        validateText(original);
        PackageData data = read(source, expectedSha256);
        Target target = target(data, original);
        return new Anchor(expectedSha256, target.bodyChildIndex(), sha(xml(target.paragraph())));
    }

    static byte[] replace(byte[] source, Anchor anchor, String original, String replacement) {
        if (anchor == null) throw new Refusal("invalid_anchor");
        validateText(original);
        validateText(replacement);
        PackageData data = read(source, anchor.sourceSha256());
        Target target = target(data, original);
        if (target.bodyChildIndex() != anchor.bodyChildIndex()
                || !sha(xml(target.paragraph())).equals(anchor.paragraphSha256())) {
            throw new Refusal("stale_anchor");
        }
        List<Element> runs = children(target.paragraph()).stream().filter(e -> is(e, W, "r")).toList();
        int offset = 0;
        for (int i = 0; i < runs.size(); i++) {
            Element text = children(runs.get(i)).stream().filter(e -> is(e, W, "t")).findFirst().orElseThrow();
            int end = i == runs.size() - 1 ? replacement.length()
                    : Math.min(replacement.length(), offset + text.getTextContent().length());
            // Keep UTF-16 surrogate pairs within a single text node.
            if (end < replacement.length() && end > offset && Character.isHighSurrogate(replacement.charAt(end - 1))) end--;
            text.setTextContent(replacement.substring(offset, end));
            text.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            offset = end;
        }
        data.parts().put(MAIN, xml(data.document()));
        byte[] result = zip(data.parts());
        // Apply the same bounds to outputs before any bytes are returned to a caller.
        read(result, sha(result));
        return result;
    }

    private static PackageData read(byte[] input, String expectedSha256) {
        if (input == null || input.length == 0 || input.length > INPUT_LIMIT) throw new Refusal("input_limit");
        byte[] source = input.clone();
        if (!sha(source).equals(expectedSha256)) throw new Refusal("source_changed");
        Path snapshot = null;
        try {
            // ZipFile validates the central directory; the private snapshot binds reads to the hashed bytes.
            snapshot = Files.createTempFile("docx-spike-", ".zip");
            Files.write(snapshot, source);
            Map<String, byte[]> parts = new LinkedHashMap<>();
            long total = 0;
            try (ZipFile archive = new ZipFile(snapshot.toFile())) {
                var entries = archive.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > ENTRY_LIMIT) throw new Refusal("entry_limit");
                    String name = entry.getName();
                    checkName(name);
                    if (entry.isDirectory() || parts.containsKey(name)) throw new Refusal("unsupported_package");
                    if (entry.getSize() < 0 || entry.getSize() > PART_LIMIT) throw new Refusal("part_limit");
                    if (entry.getCompressedSize() < 0 || entry.getSize() > RATIO_LIMIT * Math.max(1, entry.getCompressedSize())) {
                        throw new Refusal("compression_ratio");
                    }
                    total += entry.getSize();
                    if (total > EXPANDED_LIMIT) throw new Refusal("expanded_limit");
                    byte[] bytes;
                    try (var stream = archive.getInputStream(entry)) { bytes = stream.readNBytes(PART_LIMIT + 1); }
                    if (bytes.length != entry.getSize() || bytes.length > PART_LIMIT) throw new Refusal("malformed_package");
                    CRC32 crc = new CRC32(); crc.update(bytes);
                    if (crc.getValue() != entry.getCrc()) throw new Refusal("malformed_package");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("vbaproject") || lower.startsWith("word/embeddings/")
                            || lower.startsWith("word/activex/") || lower.startsWith("_xmlsignatures/")) {
                        throw new Refusal("unsupported_package");
                    }
                    if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
                        Document parsed = parse(bytes);
                        if (lower.endsWith(".rels")) validateRelationships(parsed);
                    }
                    parts.put(name, bytes);
                }
            }
            // Cross-check local headers/data descriptors too; ZipFile primarily trusts central-directory metadata.
            try (ZipInputStream local = new ZipInputStream(new ByteArrayInputStream(source))) {
                int index = 0;
                List<String> names = new ArrayList<>(parts.keySet());
                for (ZipEntry entry; (entry = local.getNextEntry()) != null;) {
                    if (index >= names.size() || !names.get(index++).equals(entry.getName())
                            || !Arrays.equals(local.readNBytes(PART_LIMIT + 1), parts.get(entry.getName()))) {
                        throw new Refusal("malformed_package");
                    }
                    if (entry.getCompressedSize() < 0
                            || entry.getSize() > RATIO_LIMIT * Math.max(1, entry.getCompressedSize())) {
                        throw new Refusal("compression_ratio");
                    }
                }
                if (index != names.size()) throw new Refusal("malformed_package");
            }
            if (!parts.keySet().containsAll(Set.of("[Content_Types].xml", "_rels/.rels", MAIN))) {
                throw new Refusal("unsupported_package");
            }
            for (var part : parts.entrySet()) {
                if (!part.getKey().endsWith(".rels")) continue;
                int relationshipDirectory = part.getKey().lastIndexOf("_rels/");
                if (relationshipDirectory < 0) throw new Refusal("unsupported_package");
                String base = part.getKey().substring(0, relationshipDirectory);
                Set<String> ids = new HashSet<>();
                for (Element relation : children(parse(part.getValue()).getDocumentElement())) {
                    if (relation.getAttribute("Id").isBlank() || !ids.add(relation.getAttribute("Id"))) throw new Refusal("unsupported_package");
                    if (!relation.getAttribute("TargetMode").equals("External")
                            && !parts.containsKey(base + relation.getAttribute("Target"))) throw new Refusal("unsupported_package");
                }
            }
            Document types = parse(parts.get("[Content_Types].xml"));
            if (!is(types.getDocumentElement(), CT, "Types")) throw new Refusal("unsupported_package");
            boolean mainType = false;
            Set<String> overrides = new HashSet<>();
            for (Element e : children(types.getDocumentElement())) {
                if (is(e, CT, "Override") && (!overrides.add(e.getAttribute("PartName"))
                        || !e.getAttribute("PartName").startsWith("/")
                        || !parts.containsKey(e.getAttribute("PartName").substring(1)))) throw new Refusal("unsupported_package");
                String type = e.getAttribute("ContentType");
                String lower = type.toLowerCase(Locale.ROOT);
                if (lower.contains("macroenabled") || lower.contains("vba") || lower.contains("oleobject")) throw new Refusal("unsupported_package");
                if (e.getAttribute("PartName").equals("/" + MAIN)) {
                    mainType = type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
                }
            }
            if (!mainType) throw new Refusal("unsupported_package");
            Document rootRels = parse(parts.get("_rels/.rels"));
            long mainRelationships = children(rootRels.getDocumentElement()).stream()
                    .filter(e -> e.getAttribute("Type").equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument")
                            && e.getAttribute("Target").equals(MAIN) && !e.getAttribute("TargetMode").equals("External")).count();
            if (mainRelationships != 1) throw new Refusal("unsupported_package");
            Document document = parse(parts.get(MAIN));
            if (!is(document.getDocumentElement(), W, "document")
                    || document.getElementsByTagNameNS(W, "body").getLength() != 1) throw new Refusal("unsupported_package");
            if (document.getElementsByTagNameNS(W, "altChunk").getLength() > 0) throw new Refusal("unsupported_package");
            return new PackageData(parts, document);
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception failure) {
            throw new Refusal("malformed_package");
        } finally {
            if (snapshot != null) {
                try { Files.deleteIfExists(snapshot); }
                catch (Exception failure) { throw new Refusal("snapshot_cleanup_failed"); }
            }
        }
    }

    private static void validateRelationships(Document document) {
        if (!is(document.getDocumentElement(), REL, "Relationships")) throw new Refusal("unsupported_package");
        for (Element relation : children(document.getDocumentElement())) {
            if (!is(relation, REL, "Relationship")) throw new Refusal("unsupported_package");
            String mode = relation.getAttribute("TargetMode");
            String type = relation.getAttribute("Type").toLowerCase(Locale.ROOT);
            if (List.of("/oleobject", "/package", "/control", "/afchunk", "/attachedtemplate", "/vbaproject")
                    .stream().anyMatch(type::endsWith)) throw new Refusal("unsupported_package");
            if (mode.equals("External")) {
                if (!relation.getAttribute("Type").equals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink")) {
                    throw new Refusal("external_resource");
                }
            } else {
                if (!mode.isEmpty() && !mode.equals("Internal")) throw new Refusal("unsupported_package");
                checkName(relation.getAttribute("Target"));
            }
        }
    }

    private static Target target(PackageData data, String original) {
        List<Element> matches = new ArrayList<>();
        for (var part : data.parts().entrySet()) {
            if (!part.getKey().startsWith("word/") || !part.getKey().endsWith(".xml")) continue;
            Document document = part.getKey().equals(MAIN) ? data.document() : parse(part.getValue());
            var paragraphs = document.getElementsByTagNameNS(W, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element p = (Element) paragraphs.item(i);
                if (paragraphText(p).equals(original)) matches.add(p);
            }
        }
        if (matches.isEmpty()) throw new Refusal("no_match");
        if (matches.size() != 1) throw new Refusal("ambiguous_match");
        Element paragraph = matches.getFirst();
        if (paragraph.getOwnerDocument() != data.document() || !is(paragraph.getParentNode(), W, "body")) {
            throw new Refusal("unsupported_target");
        }
        if (paragraph.getElementsByTagNameNS(W, "sectPr").getLength() > 0) throw new Refusal("unsupported_target");
        for (String unsupported : List.of("pPrChange", "rPrChange", "vanish", "webHidden", "specVanish")) {
            if (paragraph.getElementsByTagNameNS(W, unsupported).getLength() > 0) throw new Refusal("unsupported_target");
        }
        Element format = null;
        boolean first = true;
        int runs = 0;
        for (Element child : children(paragraph)) {
            if (is(child, W, "pPr")) continue;
            if (!is(child, W, "r")) throw new Refusal("unsupported_target");
            List<Element> texts = children(child).stream().filter(e -> is(e, W, "t")).toList();
            if (texts.size() != 1 || !children(texts.getFirst()).isEmpty()
                    || children(child).stream().filter(e -> is(e, W, "rPr")).count() > 1
                    || children(child).stream().anyMatch(e -> !is(e, W, "t") && !is(e, W, "rPr"))) {
                throw new Refusal("unsupported_target");
            }
            Element nextFormat = children(child).stream().filter(e -> is(e, W, "rPr")).findFirst().orElse(null);
            if (!first && ((format == null) != (nextFormat == null) || (format != null && !format.isEqualNode(nextFormat)))) {
                throw new Refusal("mixed_run_formatting");
            }
            format = nextFormat; first = false; runs++;
        }
        if (runs == 0) throw new Refusal("unsupported_target");
        Element body = (Element) paragraph.getParentNode();
        return new Target(paragraph, children(body).indexOf(paragraph));
    }

    static String paragraphText(Element paragraph) {
        StringBuilder result = new StringBuilder();
        collectText(paragraph, result);
        return result.toString();
    }
    private static void collectText(Node node, StringBuilder result) {
        if (is(node, W, "t") || is(node, W, "delText") || is(node, W, "instrText")) result.append(node.getTextContent());
        else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) collectText(child, result);
    }
    static Document parse(byte[] bytes) {
        if (bytes.length > XML_LIMIT) throw new Refusal("xml_limit");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("external_resource"); });
            builder.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) throws SAXException { throw new SAXException("invalid_xml"); }
                public void error(SAXParseException e) throws SAXException { throw new SAXException("invalid_xml"); }
                public void fatalError(SAXParseException e) throws SAXException { throw new SAXException("invalid_xml"); }
            });
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            if (document.getElementsByTagNameNS("http://www.w3.org/2001/XInclude", "include").getLength() > 0) throw new Refusal("external_resource");
            return document;
        } catch (Exception failure) { throw new Refusal("invalid_xml"); }
    }
    static byte[] xml(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            factory.newTransformer().transform(new DOMSource(node), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception failure) { throw new Refusal("invalid_xml"); }
    }
    static byte[] zip(Map<String, byte[]> parts) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream archive = new ZipOutputStream(output)) {
                for (var part : parts.entrySet()) {
                    ZipEntry entry = new ZipEntry(part.getKey()); entry.setTime(0);
                    archive.putNextEntry(entry); archive.write(part.getValue()); archive.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception failure) { throw new Refusal("invalid_output"); }
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new Refusal("digest_unavailable"); }
    }
    static List<Element> children(Node node) {
        List<Element> result = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element e) result.add(e);
        return result;
    }
    private static boolean is(Node node, String namespace, String name) {
        return node != null && namespace.equals(node.getNamespaceURI()) && name.equals(node.getLocalName());
    }
    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 4000
                || text.codePoints().anyMatch(c -> c < 32 || c == 0xfffe || c == 0xffff || (c >= 0xd800 && c <= 0xdfff))) {
            throw new Refusal("invalid_text");
        }
    }
    private static void checkName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains("..")
                || name.contains(":") || name.contains("%") || name.chars().anyMatch(Character::isISOControl)) {
            throw new Refusal("unsafe_part_name");
        }
    }
}
