package com.mesystem.shell;

import java.util.Arrays;
import java.util.List;
import services.market.MarketRules;
import services.quota.QuotaRules;

public final class DomainTests {
    static int run() throws Exception {
        if(QuotaRules.remaining(130)!=0) throw new AssertionError("quota remaining clamp");
        if(QuotaRules.level(10)!=QuotaRules.Level.LOW) throw new AssertionError("low quota boundary");
        System.out.println("QUOTA_TESTS_OK 2");
        if(MarketRules.compareVersions("0.10.0","0.9.7")<=0 || !MarketRules.newer("1.0.1","1.0.0") || MarketRules.newer("1.0.0","")) throw new AssertionError("version order");
        if(!MarketRules.nextVersion("1.2.9").equals("1.2.10") || !MarketRules.nextVersion("").equals("1.0.0")) throw new AssertionError("next version");
        List<MarketRules.Listing> items=Arrays.asList(new MarketRules.Listing("lucky","fun","Lucky fox","2026-10-01",900,5,1),
            new MarketRules.Listing("steady","system","Battery steady","2026-09-01",40,4.8,50),new MarketRules.Listing("fresh","system","Fresh disk","2026-10-15",10,0,0));
        if(!MarketRules.arrange(items,"","",MarketRules.Order.TOP).equals(Arrays.asList(1,0,2))) throw new AssertionError("Bayesian rating order");
        if(!MarketRules.arrange(items,"","",MarketRules.Order.POPULAR).equals(Arrays.asList(0,1,2)) || !MarketRules.arrange(items,"","",MarketRules.Order.NEW).get(0).equals(2)) throw new AssertionError("popular and new order");
        if(!MarketRules.arrange(items,"DISK fresh","system",MarketRules.Order.POPULAR).equals(Arrays.asList(2)) || !MarketRules.arrange(items,"fox","system",MarketRules.Order.TOP).isEmpty()) throw new AssertionError("search and category filter");
        if(!MarketRules.ratingBody("battery","1.0.0",4," Nice ").equals("Module: battery\nVersion: 1.0.0\nRating: 4/5\n\nNice\n")) throw new AssertionError("rating protocol");
        if(!MarketRules.issueLink("o/r","[rating] a","x y&z").equals("https://github.com/o/r/issues/new?title=%5Brating%5D+a&body=x+y%26z")) throw new AssertionError("issue link encoding");
        System.out.println("MARKET_TESTS_OK 6"); return 8;
    }
    public static void main(String[] args) throws Exception { run(); }
}
