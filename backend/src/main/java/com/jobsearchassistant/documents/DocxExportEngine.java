package com.jobsearchassistant.documents;

import org.springframework.stereotype.Component;

@Component
class DocxExportEngine {
    DocxReplacementSpike.ResolvedTarget resolve(byte[] source, String checksum, String original) {
        return DocxReplacementSpike.resolveTarget(source, checksum, original);
    }
    byte[] replace(byte[] source, DocxReplacementSpike.Anchor anchor, String original, String replacement) {
        return DocxReplacementSpike.replace(source, anchor, original, replacement);
    }
}
