package com.mesystem.shell;

import services.quota.QuotaRules;

public final class DomainTests {
    static int run() throws Exception {
        if(QuotaRules.remaining(130)!=0) throw new AssertionError("quota remaining clamp");
        if(QuotaRules.level(10)!=QuotaRules.Level.LOW) throw new AssertionError("low quota boundary");
        System.out.println("QUOTA_TESTS_OK 2"); return 2;
    }
    public static void main(String[] args) throws Exception { run(); }
}
