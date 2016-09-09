import de.qaware.chronix.database.*;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Created by mcqueen666 on 08.09.16.
 */
public class GraphiteInterface implements BenchmarkDataSource<String>{
    private final String GRAPHITE_STORAGE_DIRECTORY = "/opt/graphite/storage";
    private String ipAddress;
    private int portNumber;
    private boolean isSetup = false;
    private WebTarget graphiteClient;
    private Socket socket;
    private Writer writer;
    private Client client;

    @Override
    public boolean setup(String ipAddress, int portNumber) {
        if(!isSetup){
            try {
                client = ClientBuilder.newBuilder()
                        .register(JacksonFeature.class)
                        .property(ClientProperties.CONNECT_TIMEOUT, 30_000)
                        .property(ClientProperties.READ_TIMEOUT, 30_000)
                        .build();
                graphiteClient = client.target("http://" + ipAddress + "/");
                socket = new Socket(ipAddress, portNumber);
                writer = new OutputStreamWriter(socket.getOutputStream());
                this.ipAddress = ipAddress;
                this.portNumber = portNumber;
                isSetup = true;

            } catch (IOException e){
                System.err.println("Error initializing graphite interface: " + e.getLocalizedMessage());
                isSetup = false;
            }
        }
        return isSetup;
    }

    @Override
    public boolean clean() {
        return false;
    }

    @Override
    public void shutdown() {
        if(isSetup){
            try {
                writer.close();
                socket.close();
                client.close();
                isSetup = false;

            } catch (IOException e) {
                System.err.println("Error shutting down graphite interface: " + e.getLocalizedMessage());
            }
        }
    }

    @Override
    public String getStorageDirectoryPath() {
        return GRAPHITE_STORAGE_DIRECTORY;
    }

    @Override
    public String importDataPoints(TimeSeries timeSeries) {
        String reply = "Error: graphite was not setup!";
        if(isSetup && timeSeries != null){

            String metric = getGraphiteMetricWithTags(timeSeries.getMetricName(), timeSeries.getTagKey_tagValue());

            // write data
            int count = 0;
            int counter = 0;
            for(TimeSeriesPoint point : timeSeries.getPoints()){
                // the graphite metric string to be send
                String metricToSend = metric + " " + point.getValue() + " " + point.getTimeStamp() + " \n";

                try {
                    writer.write(metricToSend);
                    count++;
                    counter++;

                    if(counter == NUMBER_OF_POINTS_PER_BATCH){
                        writer.flush();
                        counter = 0;
                    }
                } catch (IOException e) {
                    reply = "Error importing points to graphite: " + e.getLocalizedMessage();
                }

            }
            reply = "Import of " + count + " points successful. Metric name: " + metric;

        }
        return reply;
    }

    @Override
    public String getQueryObject(BenchmarkQuery benchmarkQuery) {
        String query = "";
        if(isSetup){
            TimeSeriesMetaData metaData = benchmarkQuery.getTimeSeriesMetaData();
            QueryFunction function = benchmarkQuery.getFunction();
            String metric = getGraphiteMetricWithTags(metaData.getMetricName(), metaData.getTagKey_tagValue());

            String startDate = graphiteDateQuery(Instant.ofEpochMilli(metaData.getStart()));
            String endDate = graphiteDateQuery(Instant.ofEpochMilli(metaData.getEnd()));

            // Downsampling
            long timespan = Duration.between(Instant.ofEpochMilli(metaData.getStart()), Instant.ofEpochMilli(metaData.getEnd())).toDays() + 1;
            String aggregatedTimeSpan = timespan + "d";

            //if equals or less zero we try hours
            if(timespan <= 0){
                timespan = Duration.between(Instant.ofEpochMilli(metaData.getStart()), Instant.ofEpochMilli(metaData.getEnd())).toHours() + 1;
                aggregatedTimeSpan = timespan + "h";
            }

            //if equals or less zero we try minutes
            if(timespan <= 0){
                timespan = Duration.between(Instant.ofEpochMilli(metaData.getStart()), Instant.ofEpochMilli(metaData.getEnd())).toMinutes() + 1;
                aggregatedTimeSpan = timespan + "m";
            }

            //if equals or less zero we try millis
            if(timespan <= 0){
                timespan = Duration.between(Instant.ofEpochMilli(metaData.getStart()), Instant.ofEpochMilli(metaData.getEnd())).toMillis() + 1;
                aggregatedTimeSpan = timespan + "ms";
            }



            //[{"target": "summarize(cache.database.Global.win.global.metrics.srv.mmm.Prozessor.Total.Prozessorzeit.Percent.metric, \"1y\", \"stddev\")", "datapoints": [[113266.18400000047, 1419120000]]}]


            String summerize = "summarize(" + metric + "\"" + aggregatedTimeSpan + "\"";
            //String interval = "\"1y\"";

            switch (function) {
                case COUNT:
                    break;
                case MEAN:  query = "averageSeries(" + metric + ")";
                    break;
                case SUM:   query = summerize + ", \"sum\")";
                    break;
                case MIN:   query = summerize +  ", \"min\")";
                    break;
                case MAX:   query = summerize +  ", \"max\")";
                    break;
                case STDDEV: query = "stddevSeries(" + metric + ")";
                    break;
                case PERCENTILE:
                    Float p = benchmarkQuery.getPercentile();
                    if (p != null) {
                            query = "nPercentile(" + metric + ", " + (int)(p * 100) + ")";
                    }
                    break;
                case QUERY_ONLY:
            }

        }
        return query;
    }

    @Override
    public List<String> performQuery(BenchmarkQuery benchmarkQuery, String queryObject) {
        return null;
    }

    private String getGraphiteMetricWithTags(String metricName, Map<String, String> tags){
        String escapedMetric = escapeGraphiteMetricName(metricName);
        StringBuilder metricBuilder = new StringBuilder();
        //add tags
        for(Map.Entry<String, String> tag : tags.entrySet()){
            metricBuilder.append(escapeGraphiteMetricName(tag.getValue())).append(".");
        }
        // add metricName
        return metricBuilder.append(escapedMetric).toString();
    }


    private String escapeGraphiteMetricName(String metricName) {
        //String prefix = escape(metadata.joinWithoutMetric(), ".");
        String metric = escape(metricName, ".").replaceAll("%", "Percent").replaceAll("-", ".").replaceAll("\\.+", ".");

        //String escapedMetric = (prefix + "." + metric).replaceAll("-", ".").replaceAll("\\.+", ".");
        return metric;
    }

    private String escape(String metric, String replacement) {
        return metric.replaceAll("(\\s|\\.|:|=|,|/|\\\\|\\*|\\(|\\)|_|#)", replacement);
    }

    private String graphiteDateQuery(Instant date) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date, ZoneId.systemDefault());

        StringBuilder dateString = new StringBuilder();

        dateString.append(addDateSplit(localDateTime.getHour()))
                .append(":").
                append(addDateSplit(localDateTime.getMinute())).
                append("_").
                append(localDateTime.getYear()).
                append(addDateSplit(localDateTime.getMonthValue())).
                append(addDateSplit(localDateTime.getDayOfMonth()));


        return dateString.toString();
        //localDateTime.getHour() + ":" + localDateTime.getMinute() + "_" + localDateTime.getYear() + localDateTime.getMonthValue() + localDateTime.getDayOfMonth();
    }

    private String addDateSplit(int value) {
        if (value < 10) {
            return "0" + value;
        } else {
            return "" + value;
        }
    }
}
