package com.jobsearchassistant.documents;

import java.io.InputStream;

record BaseResumeDownload(InputStream stream, long byteSize) {
}
