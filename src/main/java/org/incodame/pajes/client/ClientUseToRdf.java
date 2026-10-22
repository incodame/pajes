package org.incodame.pajes.client;

import spoon.Launcher;
import java.io.FileWriter;
import java.io.IOException;

public class ClientUseToRdf {
    public static void main(String[] args) {
        if (args.length != 1 || args[0].trim().isEmpty()) {
            throw new IllegalArgumentException("Usage: ClientUseToRdf <target-package>");
        }

        Launcher launcher = new Launcher();
        
        launcher.addInputResource("path/to/client-module/src/main/java");
        launcher.getEnvironment().setSourceClasspath(new String[] {
            "path/to/dependencies/refmodule-implementation.jar"
        });
        launcher.getEnvironment().setNoClasspath(false);
        
        // Instanciation du processeur RDF
        ClientUseProcessor rdfProcessor = new ClientUseProcessor(args[0]);
        launcher.addProcessor(rdfProcessor);
        
        // Exécution de l'analyse Spoon
        launcher.run();
        
        // Extraction du modèle RDF finalisé
        try (FileWriter out = new FileWriter("client-use.ttl")) {
            // Écriture du modèle au format Turtle
            rdfProcessor.getModel().write(out, "TURTLE");
            System.out.println("Le rapport d'analyse RDF a été généré avec succès : client_usage_report.ttl");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
