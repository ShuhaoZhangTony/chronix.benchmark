package Docker;

import de.qaware.chronix.client.benchmark.configurator.Configurator;

/**
 * Created by mcqueen666 on 31.08.16.
 */
public class StopDockerContainer {

    public static void main(String[] args){

        Configurator configurator = Configurator.getInstance();
        String server = "localhost";
        if(args.length > 0){
            server = args[0];
        }

        System.out.println("\n###### Docker.StopDockerContainer ######");

        if(configurator.isServerUp(server)){
            System.out.println("Server is up");
        } else {
            System.out.println("Server not responding");
            return;
        }


        //stop test

        String[] answers = {"no container name given"};
        if(args != null && args.length > 1){
            for(int i = 1; i < args.length; i++) {
                String containerName = args[i];
                answers = configurator.stopDockerContainer(server, containerName);
                for(String s : answers){
                    System.out.println("Server: " + s);
                }
            }
        } else{
            for(String s : answers){
                System.out.println("Server: " + s);
            }
        }



    }
}
