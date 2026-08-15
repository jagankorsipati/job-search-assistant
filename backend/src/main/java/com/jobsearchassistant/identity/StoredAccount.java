package com.jobsearchassistant.identity;

record StoredAccount(
        AccountId id,
        LoginName loginName,
        String displayName,
        String passwordHash,
        AccountRole role,
        AccountStatus status,
        long credentialVersion,
        long version) {

    @Override
    public String toString() {
        return "StoredAccount[id=" + id + ", loginName=" + loginName + ", role=" + role
                + ", status=" + status + ", credentialVersion=" + credentialVersion
                + ", version=" + version + ", passwordHash=redacted]";
    }
}
