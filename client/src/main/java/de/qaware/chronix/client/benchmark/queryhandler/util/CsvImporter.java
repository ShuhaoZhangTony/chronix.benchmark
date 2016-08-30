package de.qaware.chronix.client.benchmark.queryhandler.util;

import de.qaware.chronix.database.TimeSeries;
import de.qaware.chronix.database.TimeSeriesPoint;

import java.io.*;
import java.text.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Created by mcqueen666 on 29.08.16.
 */
public class CsvImporter {

    /**
     * Creates time series for each metricName from a csv file.
     *
     * @apiNote File format: /measurement/host_process_group_metric.csv.(gz)
     *          file header line: Date;metricName1;metricName2;...
     *          file data:        2015-03-04T13:59:46.673Z;0.0;0.0;...
     *
     * @param csvFile the csv file from witch to import (can also be gzip compressed)
     * @return a TimeSeries for each metricName
     */
    public static List<TimeSeries> getTimeSeriesFromFile(File csvFile){
        List<TimeSeries> timeSeries = null;
        if(csvFile.exists()){
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS");
            NumberFormat nf = DecimalFormat.getInstance(Locale.ENGLISH);

            try {
                InputStream inputStream = new FileInputStream(csvFile);

                if(csvFile.getName().endsWith("gz")){
                    inputStream = new GZIPInputStream(inputStream);
                }

                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

                //read the first line
                String headerLine = bufferedReader.readLine();
                if(headerLine != null && !headerLine.isEmpty()){
                    //host _ process _ group
                    String[] fileNameMetaData = csvFile.getName().split("_");
                    String[] metrics = headerLine.split(";");
                    //build meta data object
                    String host = fileNameMetaData[0];
                    String process = fileNameMetaData[1];
                    String metricGroup = fileNameMetaData[2];
                    String measurement = csvFile.getParentFile().getName();

                    // create the tags
                    Map<String, String> tags = new HashMap<>();
                    tags.put("host", host);
                    tags.put("process", process);
                    tags.put("group",metricGroup);


                    // create metadata per metric
                    Map<Integer, TimeSeries> timeSeriesMapPerMetric = new HashMap<>();
                    for(int i = 1; i < metrics.length; i++){
                        String metric = metrics[i];
                        String metricNameOnlyAscii = Normalizer.normalize(metric, Normalizer.Form.NFD);
                        metricNameOnlyAscii = metricNameOnlyAscii.replaceAll("[^\\x00-\\x7F]", "");
                        metricNameOnlyAscii = metricNameOnlyAscii.replaceAll("\\*", "");
                        TimeSeries timeSeriesForMetric = new TimeSeries(measurement,metricNameOnlyAscii,new LinkedList<TimeSeriesPoint>(),tags,null,null);
                        timeSeriesMapPerMetric.put(i,timeSeriesForMetric);
                    }

                    // create the data points
                    String line;
                    boolean instantDate = true;
                    Instant dateObject = null;
                    while((line = bufferedReader.readLine()) != null){
                        String[] splits = line.split(";");
                        String date = splits[0];

                        try {
                            dateObject = Instant.parse(date);
                        } catch (Exception e) {
                            instantDate = false;
                        }
                        if(!instantDate){
                            try {
                                dateObject = simpleDateFormat.parse(date).toInstant();
                            } catch (ParseException e) {
                                dateObject = Instant.MIN;
                            }
                        }

                        String[] values = splits;
                        for(int column = 1; column < values.length; column++){
                            String value = values[column];
                            double numericValue = Double.MIN_VALUE;
                            try {
                                numericValue = nf.parse(value).doubleValue();
                            } catch (ParseException e) {
                            }

                            if(!dateObject.equals(Instant.MIN) && numericValue != Double.MIN_VALUE){
                                TimeSeriesPoint point = new TimeSeriesPoint(dateObject.toEpochMilli(),new Double(numericValue));
                                timeSeriesMapPerMetric.get(column).addPointToTimeSeries(point);

                            }

                        }


                    }

                    // add the points to corresponding metricnName time series
                    timeSeries = new LinkedList<>();
                    for(Map.Entry<Integer, TimeSeries> entry : timeSeriesMapPerMetric.entrySet()){
                        TimeSeries ts = entry.getValue();
                        List<TimeSeriesPoint> allPoints = ts.getPoints();
                        List<Long> timeStamps = new LinkedList<>();
                        allPoints.forEach(point -> timeStamps.add(point.getTimeStamp()));
                        Long start = Collections.min(timeStamps);
                        Long end = Collections.max(timeStamps);
                        ts.setStart(start);
                        ts.setEnd(end);
                        timeSeries.add(ts);
                    }

                }

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return timeSeries;
    }
}
