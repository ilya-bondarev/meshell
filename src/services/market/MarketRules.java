package services.market;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue ordering, search and versions, plus the GitHub issue protocol read by the meshell-tools workflows.
 * The protocol is owned by that catalogue (docs/CATALOG_RULES.md); the app only writes it.
 */
public final class MarketRules {
    private MarketRules() { }
    public enum Order { POPULAR, TOP, NEW }
    /** Votes a module needs before its own average outweighs the catalogue-wide prior. */
    static final int PRIOR_VOTES=3;
    static final double PRIOR_RATING=3.5;
    /** GitHub rejects longer prefilled issue links; longer bodies go through the clipboard. */
    public static final int LINK_LIMIT=7000;

    public static final class Listing {
        public final String id,category,text,updated; public final long downloads; public final double rating; public final int votes;
        public Listing(String id,String category,String text,String updated,long downloads,double rating,int votes) {
            this.id=id; this.category=category; this.text=text; this.updated=updated; this.downloads=downloads; this.rating=rating; this.votes=votes;
        }
    }

    /** Bayesian average, so a single 5 does not outrank a module rated 4.8 by fifty people. */
    public static double score(double rating,int votes) { return (PRIOR_RATING*PRIOR_VOTES+rating*votes)/(PRIOR_VOTES+votes); }
    /** Every whitespace-separated query word appears in the text, ignoring case. */
    public static boolean matches(String query,String text) {
        String haystack=text.toLowerCase(Locale.ROOT);
        for(String word:query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if(!word.isEmpty() && !haystack.contains(word)) return false;
        return true;
    }
    /** Indexes of the listings that match the query and category, in the requested order. */
    public static List<Integer> arrange(List<Listing> items,String query,String category,Order order) {
        List<Integer> out=new ArrayList<>();
        for(int i=0;i<items.size();i++) { Listing item=items.get(i); if((category.isEmpty() || category.equals(item.category)) && matches(query,item.text)) out.add(i); }
        Collections.sort(out,(a,b) -> {
            Listing x=items.get(a),y=items.get(b); int result;
            switch(order) {
                case TOP: result=Double.compare(score(y.rating,y.votes),score(x.rating,x.votes)); if(result==0) result=Long.compare(y.downloads,x.downloads); break;
                case NEW: result=y.updated.compareTo(x.updated); break;
                default: result=Long.compare(y.downloads,x.downloads); if(result==0) result=Double.compare(score(y.rating,y.votes),score(x.rating,x.votes));
            }
            return result!=0?result:x.id.compareTo(y.id);
        });
        return out;
    }

    public static boolean version(String value) { return value.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"); }
    /** Numeric comparison of dotted versions; missing parts count as zero. */
    public static int compareVersions(String a,String b) {
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++) {
            int difference=Long.compare(part(x,i),part(y,i)); if(difference!=0) return difference;
        }
        return 0;
    }
    private static long part(String[] parts,int i) { try { return i<parts.length?Long.parseLong(parts[i].replaceAll("[^0-9].*$","")):0; } catch(NumberFormatException e) { return 0; } }
    public static boolean newer(String candidate,String installed) { return !installed.isEmpty() && compareVersions(candidate,installed)>0; }
    /** Suggested version for the next submission: the first release, or the following patch. */
    public static String nextVersion(String published) {
        if(!version(published)) return "1.0.0";
        String[] parts=published.split("\\."); return parts[0]+"."+parts[1]+"."+(Long.parseLong(parts[2])+1);
    }

    public static String ratingTitle(String id) { return "[rating] "+id; }
    public static String ratingBody(String id,String version,int stars,String review) {
        if(stars<1 || stars>5) throw new IllegalArgumentException("rating");
        String text=review.trim(); return "Module: "+id+"\nVersion: "+version+"\nRating: "+stars+"/5\n"+(text.isEmpty()?"":"\n"+text+"\n");
    }
    public static String submissionTitle(String id) { return "[submit] "+id; }
    public static String submissionBody(String json) {
        return "MeShell module submission. A maintainer reviews the commands and permissions before publishing.\n\n```json\n"+json+"\n```\n";
    }
    public static String reportTitle(String id) { return "[report] "+id; }
    public static String requestTitle(String text) {
        String value=text.trim().replaceAll("\\s+"," "); return "[request] "+(value.length()>80?value.substring(0,80):value);
    }
    public static String issueLink(String repository,String title,String body) {
        return "https://github.com/"+repository+"/issues/new?title="+encode(title)+"&body="+encode(body);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value,"UTF-8"); } catch(UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
}
