package com.jobsearchassistant.fit;

import java.math.BigDecimal;

record FitScore(
        int score,
        BigDecimal numerator,
        int denominator) {
}
