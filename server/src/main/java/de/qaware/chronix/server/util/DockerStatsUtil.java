package de.qaware.chronix.server.util;

import com.sun.management.OperatingSystemMXBean;
import de.qaware.chronix.shared.DataModels.Pair;
import de.qaware.chronix.shared.DataModels.Tuple;

import java.lang.management.ManagementFactory;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by mcqueen666 on 19.08.16.
 */
public class DockerStatsUtil {
    private static final long MEASURE_INTERVAL_MILLISECONDS = 100;
    private static final long DOCKER_STATS_REACTION_MILLISECONDS = 1000;

    private static DockerStatsUtil instance;
    private MeasureRunner[] threads;


    private DockerStatsUtil(){
        OperatingSystemMXBean oSMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        threads = new MeasureRunner[oSMXBean.getAvailableProcessors()];

    }

    public static synchronized DockerStatsUtil getInstance(){
        if(instance == null){
            instance = new DockerStatsUtil();
        }
        return instance;
    }


    /**
     * Starts a threaded docker stats measurement of given container id.
     *
     * @param containerID the containerID of the container to be measured.
     */
    public void startDockerContainerMeasurement(String containerID){
        if(containerID != null && !containerID.isEmpty()) {
            for(int i = 0; i < threads.length; i++) {
                threads[i] = new MeasureRunner(containerID, true);
                threads[i].start();
                try {
                    Thread.sleep(DOCKER_STATS_REACTION_MILLISECONDS / threads.length);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }
    }

    /**
     * Ends the previously started docker stats measurement.
     *
     * @return A list of Pairs containing as Pair.first the cpu usage in % and as Pair.second the memory usage in %
     */
    public List<Tuple<Double,Double, Long, Long>> stopDockerContainerMeasurement(){
        List<Tuple<Double,Double, Long, Long>> completeMeasures = new LinkedList<>();
        for(int i = 0; i < threads.length; i++){
            threads[i].stopRunning();
            try {
                threads[i].join(2 * DOCKER_STATS_REACTION_MILLISECONDS);
                completeMeasures.addAll(threads[i].getMeasures());

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return completeMeasures;
    }


    /**
     * Estimates the storage size of the given container.
     *
     * @param containerName the name of the docker container
     * @param storageDirectoryPath the path to the storage directory in the container of the running tsdb.
     * @return the size of the storage directory in the container in bytes.
     */
    public Long estimateStorageSize(String containerName, String storageDirectoryPath){
        Long resultBytes = new Long(-1);
        String containerID = DockerCommandLineUtil.getRunningContainerId(containerName);
        if(!containerID.isEmpty()){
            String[] command = ServerSystemUtil.getOsSpecificCommand(new String[]{DockerCommandLineUtil.getDockerInstallPath()
                    + "docker exec -t "
                    + containerID
                    + " /usr/bin/du -c -b --max=1 "
                    + storageDirectoryPath
                    + " | awk '{print $1}'"});

            List<String> answers = ServerSystemUtil.executeCommand(command);
            if(!answers.isEmpty()){
                resultBytes = Long.valueOf(answers.get(answers.size()-1));
            }
        }

        return resultBytes;
    }


    private class MeasureRunner extends Thread{
        private List<Tuple<Double,Double,Long,Long>> measures = new LinkedList<>();
        private String containerID;
        private String[] command;
        private volatile boolean running;

        public MeasureRunner(String containerID, boolean running){
            this.containerID = containerID;
            command = ServerSystemUtil.getOsSpecificCommand(new String[]{DockerCommandLineUtil.getDockerInstallPath()
                    + "docker stats "
                    + containerID
                    + " --no-stream | grep "
                    + containerID
                    + " | awk '{print $2 $8 $14$15\"%\" $17$18\"%\"}'"});
            this.running = running;
        }

        public List<Tuple<Double,Double,Long,Long>> getMeasures(){
            return measures;
        }

        public synchronized void stopRunning(){
            this.running = false;
        }

        public void run(){
            while(this.running) {
                List<String> answers = ServerSystemUtil.executeCommand(command);
                String[] splits = answers.get(0).split("%");
                if(splits.length == 4) {
                    Tuple<Double, Double, Long, Long> record = Tuple.of(
                            Double.valueOf(splits[0]),
                            Double.valueOf(splits[1]),
                            getBytesCountFromString(splits[2]),
                            getBytesCountFromString(splits[3]));
                    measures.add(record);
                }
            }

        }

        private Long getBytesCountFromString(String s){
            Double result = new Double(-1);
            Character last = s.charAt(s.length()-1);
            Character secondLast = s.charAt(s.length()-2);

            if(last == 'B') {
                if (Character.getType(secondLast) == Character.LOWERCASE_LETTER || Character.getType(secondLast) == Character.UPPERCASE_LETTER) {
                    result = Double.valueOf(s.substring(0, s.length()-2));
                    switch (secondLast){
                        case 'k': result *= 1000;
                            break;
                        case 'M': result *= 1000 * 1000;
                            break;
                        case 'G': result *= 1000 * 1000 * 1000;
                            break;
                    }
                } else {
                    // secondLast is not a letter -> only Bytes here
                    result = Double.valueOf(s.substring(0, s.length()-1));
                }
            }
            return result.longValue();
        }


    }

}
