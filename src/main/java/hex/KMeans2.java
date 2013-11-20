package hex;

import hex.KMeans.Initialization;

import java.util.*;

import water.*;
import water.Job.ColumnsJob;
import water.api.*;
import water.api.RequestBuilders.Response;
import water.fvec.Chunk;
import water.fvec.Vec;
import water.util.Utils;

/**
 * Scalable K-Means++ (KMeans||)<br>
 * http://theory.stanford.edu/~sergei/papers/vldb12-kmpar.pdf<br>
 * http://www.youtube.com/watch?v=cigXAxV3XcY
 */
public class KMeans2 extends ColumnsJob {
  static final int API_WEAVER = 1;
  static public DocGen.FieldDoc[] DOC_FIELDS;
  static final String DOC_GET = "k-means";

  @API(help = "Clusters initialization", filter = Default.class)
  public Initialization initialization = Initialization.None;

  @API(help = "Number of clusters", required = true, filter = Default.class, lmin = 2, lmax = 100000)
  public int k = 2;

  @API(help = "Maximum number of iterations before stopping", required = true, filter = Default.class, lmin = 1, lmax = 100000)
  public int max_iter = 100;

  @API(help = "Whether data should be normalized", filter = Default.class)
  public boolean normalize;

  @API(help = "Seed for the random number generator", filter = Default.class)
  public long seed = new Random().nextLong();

  public KMeans2() {
    description = "K-means";
  }

  @Override protected void exec() {
    String sourceArg = input("source");
    Key sourceKey = null;
    if( sourceArg != null )
      sourceKey = Key.make(sourceArg);
    String[] names = new String[cols.length];
    for( int i = 0; i < cols.length; i++ )
      names[i] = source._names[cols[i]];
    Vec[] vecs = selectVecs(source);
    // Fill-in response based on K
    String[] domain = new String[k];
    for( int i = 0; i < domain.length; i++ )
      domain[i] = "Cluster " + i;
    String[] namesResp = Utils.append(names, "response");
    String[][] domaiResp = (String[][]) Utils.append(source.domains(), (Object) domain);
    KMeans2Model model = new KMeans2Model(destination_key, sourceKey, namesResp, domaiResp);

    // TODO remove when stats are propagated with vecs?
    double[] means = new double[vecs.length];
    double[] mults = normalize ? new double[vecs.length] : null;
    for( int i = 0; i < vecs.length; i++ ) {
      means[i] = (float) vecs[i].mean();
      if( mults != null ) {
        double sigma = vecs[i].sigma();
        mults[i] = normalize(sigma) ? 1 / sigma : 1;
      }
    }

    // -1 to be different from all chunk indexes (C.f. Sampler)
    Random rand = Utils.getRNG(seed - 1);
    double[][] clusters;
    if( initialization == Initialization.None ) {
      // Initialize all clusters to random rows
      clusters = new double[k][vecs.length];
      for( int i = 0; i < clusters.length; i++ )
        randomRow(vecs, rand, clusters[i], means, mults);
    } else {
      // Initialize first cluster to random row
      clusters = new double[1][];
      clusters[0] = new double[vecs.length];
      randomRow(vecs, rand, clusters[0], means, mults);

      while( model.iterations < 5 ) {
        // Sum squares distances to clusters
        SumSqr sqr = new SumSqr();
        sqr._clusters = clusters;
        sqr._means = means;
        sqr._mults = mults;
        sqr.doAll(vecs);

        // Sample with probability inverse to square distance
        Sampler sampler = new Sampler();
        sampler._clusters = clusters;
        sampler._sqr = sqr._sqr;
        sampler._probability = k * 3; // Over-sampling
        sampler._seed = seed;
        sampler._means = means;
        sampler._mults = mults;
        sampler.doAll(vecs);
        clusters = Utils.append(clusters, sampler._sampled);

        if( cancelled() )
          return;
        model.clusters = normalize ? denormalize(clusters, vecs) : clusters;
        model.error = sqr._sqr;
        model.iterations++;
        UKV.put(destination_key, model);
      }

      clusters = recluster(clusters, k, rand, initialization);
    }

    for( ;; ) {
      Lloyds task = new Lloyds();
      task._clusters = clusters;
      task._means = means;
      task._mults = mults;
      task.doAll(vecs);
      model.clusters = normalize ? denormalize(task._cMeans, vecs) : task._cMeans;
      double[] variances = new double[task._cSqrs.length];
      for( int clu = 0; clu < task._cSqrs.length; clu++ )
        for( int col = 0; col < task._cSqrs[clu].length; col++ )
          variances[clu] += task._cSqrs[clu][col];
      model.cluster_variances = variances;
      model.error = task._sqr;
      model.iterations++;
      UKV.put(destination_key, model);
      if( model.iterations >= max_iter )
        break;
      if( cancelled() )
        break;
    }
  }

  @Override protected Response redirect() {
    return KMeans2Progress.redirect(this, job_key, destination_key);
  }

  public static class KMeans2Progress extends Progress2 {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @Override protected Response jobDone(Job job, Key dst) {
      return KMeans2ModelView.redirect(this, destination_key);
    }

    public static Response redirect(Request req, Key job_key, Key destination_key) {
      return Response.redirect(req, new KMeans2Progress().href(), JOB_KEY, job_key, DEST_KEY, destination_key);
    }
  }

  public static class KMeans2ModelView extends Request2 {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "KMeans2 Model", json = true, filter = Default.class)
    public KMeans2Model model;

    public static String link(String txt, Key model) {
      return "<a href='" + new KMeans2ModelView().href() + ".html?model=" + model + "'>" + txt + "</a>";
    }

    public static Response redirect(Request req, Key model) {
      return Response.redirect(req, new KMeans2ModelView().href(), "model", model);
    }

    @Override protected Response serve() {
      return Response.done(this);
    }

    @Override public boolean toHTML(StringBuilder sb) {
      if( model != null ) {
        DocGen.HTML.section(sb, "Error: " + model.error);
        table(sb, "Clusters", model._names, model.clusters);
        double[][] rows = new double[model.cluster_variances.length][1];
        for( int i = 0; i < rows.length; i++ )
          rows[i][0] = model.cluster_variances[i];
        table(sb, "In-cluster variances", model._names, rows);
        return true;
      }
      return false;
    }

    private static void table(StringBuilder sb, String title, String[] names, double[][] rows) {
      sb.append("<span style='display: inline-block;'>");
      sb.append("<table class='table table-striped table-bordered'>");
      sb.append("<tr>");
      sb.append("<th>" + title + "</th>");
      for( int i = 0; names != null && i < rows[0].length; i++ )
        sb.append("<th>").append(names[i]).append("</th>");
      sb.append("</tr>");
      for( int r = 0; r < rows.length; r++ ) {
        sb.append("<tr>");
        sb.append("<td>").append(r).append("</td>");
        for( int c = 0; c < rows[r].length; c++ )
          sb.append("<td>").append(ElementBuilder.format(rows[r][c])).append("</td>");
        sb.append("</tr>");
      }
      sb.append("</table></span>");
    }
  }

  public static class KMeans2Model extends Model implements Progress {
    static final int API_WEAVER = 1;
    static public DocGen.FieldDoc[] DOC_FIELDS;

    @API(help = "Cluster centers, always denormalized")
    public double[][] clusters;

    @API(help = "Sum of min square distances")
    public double error;

    @API(help = "Whether data was normalized")
    public boolean normalized;

    @API(help = "Maximum number of iterations before stopping")
    public int max_iter = 100;

    @API(help = "Iterations the algorithm ran")
    public int iterations;

    @API(help = "Sum of square distances per cluster")
    public double[] cluster_variances;

    // Normalization caches
    private transient double[][] _normClust;
    private transient double[] _means, _mults;

    public KMeans2Model(Key selfKey, Key dataKey, String names[], String domains[][]) {
      super(selfKey, dataKey, names, domains);
    }

    @Override public float progress() {
      return Math.min(1f, iterations / (float) max_iter);
    }

    @Override protected float[] score0(Chunk[] chunks, int rowInChunk, double[] tmp, float[] preds) {
      double[][] cs = clusters;
      if( normalized && _normClust == null )
        cs = _normClust = normalize(clusters, chunks);
      if( _means == null ) {
        _means = new double[chunks.length];
        for( int i = 0; i < chunks.length; i++ )
          _means[i] = chunks[i]._vec.mean();
      }
      if( normalized && _mults == null ) {
        _mults = new double[chunks.length];
        for( int i = 0; i < chunks.length; i++ ) {
          double sigma = chunks[i]._vec.sigma();
          _mults[i] = normalize(sigma) ? 1 / sigma : 1;
        }
      }
      data(tmp, chunks, rowInChunk, _means, _mults);
      Arrays.fill(preds, 0);
      preds[closest(cs, tmp, new ClusterDist())._cluster] = 1;
      return preds;
    }

    @Override protected float[] score0(double[] data, float[] preds) {
      throw new UnsupportedOperationException();
    }
  }

  public static class SumSqr extends MRTask2<SumSqr> {
    // IN
    double[] _means, _mults; // Normalization
    double[][] _clusters;

    // OUT
    double _sqr;

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ClusterDist cd = new ClusterDist();
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        _sqr += minSqr(_clusters, values, cd);
      }
      _means = _mults = null;
      _clusters = null;
    }

    @Override public void reduce(SumSqr other) {
      _sqr += other._sqr;
    }
  }

  public static class Sampler extends MRTask2<Sampler> {
    // IN
    double[][] _clusters;
    double _sqr;           // Min-square-error
    double _probability;   // Odds to select this point
    long _seed;
    double[] _means, _mults; // Normalization

    // OUT
    double[][] _sampled;   // New clusters

    @Override public void map(Chunk[] cs) {
      double[] values = new double[cs.length];
      ArrayList<double[]> list = new ArrayList<double[]>();
      Random rand = Utils.getRNG(_seed + cs[0]._start);
      ClusterDist cd = new ClusterDist();

      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        double sqr = minSqr(_clusters, values, cd);
        if( _probability * sqr > rand.nextDouble() * _sqr )
          list.add(values.clone());
      }

      _sampled = new double[list.size()][];
      list.toArray(_sampled);
      _clusters = null;
      _means = _mults = null;
    }

    @Override public void reduce(Sampler other) {
      _sampled = Utils.append(_sampled, other._sampled);
    }
  }

  public static class Lloyds extends MRTask2<Lloyds> {
    // IN
    double[][] _clusters;
    double[] _means, _mults;      // Normalization

    // OUT
    double[][] _cMeans, _cSqrs; // Means and sum of squares for each cluster
    long[] _rows;               // Rows per cluster
    double _sqr;                // Total sqr distance

    @Override public void map(Chunk[] cs) {
      _cMeans = new double[_clusters.length][_clusters[0].length];
      _cSqrs = new double[_clusters.length][_clusters[0].length];
      _rows = new long[_clusters.length];

      // Find closest cluster for each row
      double[] values = new double[_clusters[0].length];
      ClusterDist cd = new ClusterDist();
      int[] clusters = new int[cs[0]._len];
      for( int row = 0; row < cs[0]._len; row++ ) {
        data(values, cs, row, _means, _mults);
        closest(_clusters, values, cd);
        int clu = clusters[row] = cd._cluster;
        _sqr += cd._dist;
        if( clu == -1 )
          continue; // Ignore broken row

        // Add values and increment counter for chosen cluster
        for( int col = 0; col < values.length; col++ )
          _cMeans[clu][col] += values[col];
        _rows[clu]++;
      }
      for( int clu = 0; clu < _cMeans.length; clu++ )
        for( int col = 0; col < _cMeans[clu].length; col++ )
          _cMeans[clu][col] /= _rows[clu];
      // Second pass for in-cluster variances
      for( int row = 0; row < cs[0]._len; row++ ) {
        int clu = clusters[row];
        if( clu == -1 )
          continue;
        data(values, cs, row, _means, _mults);
        for( int col = 0; col < values.length; col++ ) {
          double delta = values[col] - _cMeans[clu][col];
          _cSqrs[clu][col] += delta * delta;
        }
      }
      _clusters = null;
      _means = _mults = null;
    }

    @Override public void reduce(Lloyds mr) {
      for( int clu = 0; clu < _cMeans.length; clu++ )
        Layer.Stats.reduce(_cMeans[clu], _cSqrs[clu], _rows[clu], mr._cMeans[clu], mr._cSqrs[clu], mr._rows[clu]);
      Utils.add(_rows, mr._rows);
      _sqr += mr._sqr;
    }
  }

  private static final class ClusterDist {
    int _cluster;
    double _dist;
  }

  private static ClusterDist closest(double[][] clusters, double[] point, ClusterDist cd) {
    return closest(clusters, point, cd, clusters.length);
  }

  private static double minSqr(double[][] clusters, double[] point, ClusterDist cd) {
    return closest(clusters, point, cd, clusters.length)._dist;
  }

  private static double minSqr(double[][] clusters, double[] point, ClusterDist cd, int count) {
    return closest(clusters, point, cd, count)._dist;
  }

  /** Return both nearest of N cluster/centroids, and the square-distance. */
  private static ClusterDist closest(double[][] clusters, double[] point, ClusterDist cd, int count) {
    int min = -1;
    double minSqr = Double.MAX_VALUE;
    for( int cluster = 0; cluster < count; cluster++ ) {
      double sqr = 0;           // Sum of dimensional distances
      int pts = point.length;   // Count of valid points
      for( int column = 0; column < clusters[cluster].length; column++ ) {
        double d = point[column];
        if( Double.isNaN(d) ) { // Bad data?
          pts--;                // Do not count
        } else {
          double delta = d - clusters[cluster][column];
          sqr += delta * delta;
        }
      }
      // Scale distance by ratio of valid dimensions to all dimensions - since
      // we did not add any error term for the missing point, the sum of errors
      // is small - ratio up "as if" the missing error term is equal to the
      // average of other error terms.  Same math another way:
      //   double avg_dist = sqr / pts; // average distance per feature/column/dimension
      //   sqr = sqr * point.length;    // Total dist is average*#dimensions
      if( 0 < pts && pts < point.length )
        sqr *= point.length / pts;
      if( sqr < minSqr ) {
        min = cluster;
        minSqr = sqr;
      }
    }
    cd._cluster = min;          // Record nearest cluster
    cd._dist = minSqr;          // Record square-distance
    return cd;                  // Return for flow-coding
  }

  // KMeans++ re-clustering
  public static double[][] recluster(double[][] points, int k, Random rand, Initialization init) {
    double[][] res = new double[k][];
    res[0] = points[0];
    int count = 1;
    ClusterDist cd = new ClusterDist();
    switch( init ) {
      case PlusPlus: { // k-means++
        while( count < res.length ) {
          double sum = 0;
          for( int i = 0; i < points.length; i++ )
            sum += minSqr(res, points[i], cd, count);

          for( int i = 0; i < points.length; i++ ) {
            if( minSqr(res, points[i], cd, count) >= rand.nextDouble() * sum ) {
              res[count++] = points[i];
              break;
            }
          }
        }
        break;
      }
      case Furthest: { // Takes cluster further from any already chosen ones
        while( count < res.length ) {
          double max = 0;
          int index = 0;
          for( int i = 0; i < points.length; i++ ) {
            double sqr = minSqr(res, points[i], cd, count);
            if( sqr > max ) {
              max = sqr;
              index = i;
            }
          }
          res[count++] = points[index];
        }
        break;
      }
      default:
        throw new IllegalStateException();
    }
    return res;
  }

  private void randomRow(Vec[] vecs, Random rand, double[] cluster, double[] means, double[] mults) {
    long row = Math.max(0, (long) (rand.nextDouble() * vecs[0].length()) - 1);
    data(cluster, vecs, row, means, mults);
  }

  private static boolean normalize(double sigma) {
    // TODO unify handling of constant columns
    return sigma > 1e-6;
  }

  private static double[][] normalize(double[][] clusters, Chunk[] chks) {
    double[][] value = new double[clusters.length][clusters[0].length];
    for( int row = 0; row < value.length; row++ ) {
      for( int col = 0; col < clusters[row].length; col++ ) {
        double d = clusters[row][col];
        Vec vec = chks[col]._vec;
        d -= vec.mean();
        d /= normalize(vec.sigma()) ? vec.sigma() : 1;
        value[row][col] = d;
      }
    }
    return value;
  }

  private static double[][] denormalize(double[][] clusters, Vec[] vecs) {
    double[][] value = new double[clusters.length][clusters[0].length];
    for( int row = 0; row < value.length; row++ ) {
      for( int col = 0; col < clusters[row].length; col++ ) {
        double d = clusters[row][col];
        d *= vecs[col].sigma();
        d += vecs[col].mean();
        value[row][col] = d;
      }
    }
    return value;
  }

  private static void data(double[] values, Vec[] vecs, long row, double[] means, double[] mults) {
    for( int i = 0; i < values.length; i++ ) {
      double d = vecs[i].at(row);
      values[i] = data(d, i, means, mults);
    }
  }

  private static void data(double[] values, Chunk[] chks, int row, double[] means, double[] mults) {
    for( int i = 0; i < values.length; i++ ) {
      double d = chks[i].at0(row);
      values[i] = data(d, i, means, mults);
    }
  }

  /**
   * Takes mean if NaN, normalize if requested.
   */
  private static double data(double d, int i, double[] means, double[] mults) {
    if( Double.isNaN(d) )
      d = means[i];
    if( mults != null ) {
      d -= means[i];
      d *= mults[i];
    }
    return d;
  }
}
