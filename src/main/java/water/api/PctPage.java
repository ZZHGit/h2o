package water.api;

import hex.GroupedPct;
import hex.Percentile;
import water.Key;
import water.Request2;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.RString;

public class PctPage extends Request2 {
  static final int API_WEAVER=1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  // This Request supports the HTML 'GET' command, and this is the help text
  // for GET.
  static final String DOC_GET = "Returns group-by percentiles.";

  @API(help="An existing H2O Frame key.", required=true, filter=Default.class)
  Frame source;

  class colFilter extends VecSelect { public colFilter() { super("source");} }
  class colsFilter extends MultiVecSelect { public colsFilter() { super("source");} }
  @API(help = "Select column to group by.", required = true, filter= colsFilter.class)
  int[] gcols;

  @API(help = "Select the value column.", required = true, filter= colFilter.class)
  Vec   vcol;

  @API(help = "Percentiles over groups.")
  Percentile.Summary[] gpct;

  public static String link(Key k, String content) {
    RString rs = new RString("<a href='PctPage.query?source=%$key'>"+content+"</a>");
    rs.replace("key", k.toString());
    return rs.toString();
  }

  @Override protected Response serve() {
    if( source == null ) return RequestServer._http404.serve();
    // select all columns
    Percentile pct = new Percentile(source, source.find(vcol)).groupBy(gcols);
    gpct = pct._gsums;
    source.add(pct.getPctCols());
    // refactoring...
    Percentile[] pcts = new PercentileAlgo(vcol)
            .withFilter(new GroupByFilter(int[] gcols))
            .do(source);
    return Response.done(this);
  }

  @Override public boolean toHTML( StringBuilder sb ) {
    sb.append("<div class=container-fluid'>");
    sb.append("<div class='row-fluid'>");
    for( int i = 0; i < gpct.length; i++) {
      gpct[i].toHTML(sb);
    }
    sb.append("</div>");
    sb.append("</div>");
    return true;
  }
}
