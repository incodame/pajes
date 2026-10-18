import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.FileOutputStream;
import java.io.IOException;

public class JavaPatchToRdf {

    // Espace de noms officiel de CodeOntology
    private static final String WOC_NS = "http://webofcode.org";
    // Espace de noms personnalisé pour votre projet / votre pont logique
    private static final String EX_NS = "https://github.com/Incodam";

    public static void main(String[] args) {
        // 1. Initialiser le modèle Apache Jena
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("woc", WOC_NS);
        model.setNsPrefix("icodm", EX_NS);
        model.setNsPrefix("rdf", RDF.getURI());

        // Création des propriétés et classes sémantiques
        Resource wocMethodClass = model.createResource(WOC_NS + "Method");
        Resource wocParameterClass = model.createResource(WOC_NS + "Parameter");
        
        Property wocName = model.createProperty(WOC_NS + "name");
        Property wocHasModifier = model.createProperty(WOC_NS + "hasModifier");
        Property wocHasReturnType = model.createProperty(WOC_NS + "hasReturnType");
        Property wocHasParameter = model.createProperty(WOC_NS + "hasParameter");
        Property wocParamIndex = model.createProperty(WOC_NS + "parameterIndex");
        Property wocParamType = model.createProperty(WOC_NS + "parameterType");
        Property wocThrowsException = model.createProperty(WOC_NS + "throwsException");
        
        // Votre extension pour lier le patch à la version stable
        Property exIsPatchOfMethod = model.createProperty(EX_NS + "isPatchOfMethod");

        // 2. Configurer Spoon pour parser uniquement le fichier modifié du patch
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true); // Crucial pour analyser un fichier isolé du patch
        
        // REMPLACEZ par le chemin du fichier extrait de votre "git diff"
        launcher.addInputResource("/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java"); 
        launcher.buildModel();
        CtModel spoonModel = launcher.getModel();

        // 3. Parcourir l'AST Spoon et générer le graphe Jena
        for (CtType<?> type : spoonModel.getAllTypes()) {
            String fqcn = type.getQualifiedName();

            for (CtMethod<?> method : type.getMethods()) {
                // On se concentre sur l'API publique et protégée (les contrats)
                if (method.isPublic() || method.isProtected()) {
                    String signature = method.getSignature(); // ex: "processData(int)"
                    String visibility = method.isPublic() ? "public" : "protected";

                    // URI unique pour la méthode patchée
                    String patchedMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_patched";
                    // URI de la méthode stable (générée de la même manière lors de l'analyse Revapi/Stable précédente)
                    String stableMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_stable";

                    Resource patchedMethodRes = model.createResource(patchedMethodURI)
                            .addProperty(RDF.type, wocMethodClass)
                            .addProperty(wocName, method.getSimpleName())
                            .addProperty(wocHasModifier, visibility)
                            .addProperty(wocHasReturnType, method.getType().getQualifiedName())
                            .addProperty(exIsPatchOfMethod, model.createResource(stableMethodURI));

                    // Extraction des paramètres
                    int index = 0;
                    for (CtParameter<?> param : method.getParameters()) {
                        String paramURI = patchedMethodURI + "_param_" + index;
                        
                        Resource paramRes = model.createResource(paramURI)
                                .addProperty(RDF.type, wocParameterClass)
                                .addProperty(wocParamIndex, model.createTypedLiteral(index))
                                .addProperty(wocParamType, param.getType().getQualifiedName());

                        patchedMethodRes.addProperty(wocHasParameter, paramRes);
                        index++;
                    }

                    // Extraction des exceptions levées (throws)
                    for (CtTypeReference<? extends Throwable> excRef : method.getThrownTypes()) {
                        patchedMethodRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                    }
                }
            }
        }

        // 4. Exporter le modèle au format Turtle pour RDFox
        try (FileOutputStream out = new FileOutputStream("refmodule-patch.ttl")) {
            model.write(out, "TURTLE");
            System.out.println("Fichier RDF 'refmodule-patch.ttl' généré avec succès !");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
