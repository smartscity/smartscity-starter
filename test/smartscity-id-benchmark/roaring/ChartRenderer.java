import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.LogarithmicAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;

/**
 * Renders the recorded comparison series with JFreeChart 1.5.5, outside the SDK.
 *
 * @author jack
 */
public final class ChartRenderer {
  private ChartRenderer() {}

  /** Renders a tab-separated series file into seven standalone PNG charts. */
  public static void main(String[] args) throws Exception {
    Map<String, DefaultCategoryDataset> data = new LinkedHashMap<>();
    Map<String, String[]> meta = new LinkedHashMap<>();
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      String[] f = line.split("\t");
      data.computeIfAbsent(f[0], ignored -> new DefaultCategoryDataset())
          .addValue(Double.parseDouble(f[3]), f[1], f[2]);
      meta.put(f[0], f);
    }
    File directory = new File(args[1]);
    directory.mkdirs();
    for (Map.Entry<String, DefaultCategoryDataset> entry : data.entrySet()) {
      String name = entry.getKey();
      String[] f = meta.get(name);
      boolean line = name.startsWith("generator-") || name.equals("end-to-end");
      JFreeChart chart = line
          ? ChartFactory.createLineChart(f[5], f[6], f[4], entry.getValue(),
              PlotOrientation.VERTICAL, true, false, false)
          : ChartFactory.createBarChart(f[5], f[6], f[4], entry.getValue(),
              PlotOrientation.VERTICAL, true, false, false);
      chart.setBackgroundPaint(Color.WHITE);
      chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 17));
      CategoryPlot plot = chart.getCategoryPlot();
      plot.setBackgroundPaint(Color.WHITE);
      plot.setRangeGridlinePaint(new Color(210, 210, 210));
      plot.getDomainAxis().setTickLabelFont(new Font("SansSerif", Font.PLAIN, 11));
      if (name.equals("generator-p99") || name.equals("heap") || name.equals("containers")) {
        LogarithmicAxis axis = new LogarithmicAxis(f[4]);
        axis.setAutoRangeIncludesZero(false);
        if (plot.getRenderer() instanceof BarRenderer) {
          ((BarRenderer) plot.getRenderer()).setIncludeBaseInRange(false);
        }
        plot.setRangeAxis(axis);
      }
      if (plot.getRenderer() instanceof BarRenderer) {
        ((BarRenderer) plot.getRenderer()).setShadowVisible(false);
      }
      ChartUtils.saveChartAsPNG(new File(directory, name + ".png"), chart, 1400, 800);
    }
  }
}
