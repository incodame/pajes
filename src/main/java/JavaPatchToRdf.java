import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.FileOutputStream;
import java.io.IOException;

public class JavaPatchToRdf {

    // Espace de noms officiel de CodeOntology
    private static final String WOC_NS = "http://webofcode.org";
    // Espace de noms personnalisé pour votre projet / votre pont logique
    // The trailing slash keeps Turtle serializer output compatible with the icodm: prefix.
    private static final String EX_NS = "https://github.com/Incodam/";

    private static String buildConstructorSignature(CtConstructor<?> constructor) {
        StringBuilder signature = new StringBuilder();
        boolean first = true;
        for (CtParameter<?> parameter : constructor.getParameters()) {
            if (!first) {
                signature.append("_");
            }
            signature.append(parameter.getType().getQualifiedName().replace('.', '_'));
            first = false;
        }
        return signature.length() == 0 ? "default" : signature.toString();
    }

    public static Model buildModelForFile(String sourcePath) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("woc", WOC_NS);
        model.setNsPrefix("icodm", EX_NS);
        model.setNsPrefix("rdf", RDF.getURI());

        Resource wocMethodClass = model.createResource(WOC_NS + "Method");
        Resource wocConstructorClass = model.createResource(WOC_NS + "Constructor");
        Resource wocParameterClass = model.createResource(WOC_NS + "Parameter");

        Property wocName = model.createProperty(WOC_NS + "name");
        Property wocHasModifier = model.createProperty(WOC_NS + "hasModifier");
        Property wocHasReturnType = model.createProperty(WOC_NS + "hasReturnType");
        Property wocHasParameter = model.createProperty(WOC_NS + "hasParameter");
        Property wocHasConstructor = model.createProperty(WOC_NS + "hasConstructor");
        Property wocParamIndex = model.createProperty(WOC_NS + "parameterIndex");
        Property wocParamType = model.createProperty(WOC_NS + "parameterType");
        Property wocThrowsException = model.createProperty(WOC_NS + "throwsException");

        Property exIsPatchOfMethod = model.createProperty(EX_NS + "isPatchOfMethod");

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(sourcePath);
        launcher.buildModel();
        CtModel spoonModel = launcher.getModel();

        for (CtType<?> type : spoonModel.getAllTypes()) {
            String fqcn = type.getQualifiedName();
            Resource classResource = model.createResource(EX_NS + fqcn);

            for (CtMethod<?> method : type.getMethods()) {
                if (method.isPublic() || method.isProtected()) {
                    String visibility = method.isPublic() ? "public" : "protected";
                    String patchedMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_patched";
                    String stableMethodURI = EX_NS + fqcn + "_" + method.getSimpleName() + "_stable";

                    Resource patchedMethodRes = model.createResource(patchedMethodURI)
                            .addProperty(RDF.type, wocMethodClass)
                            .addProperty(wocName, method.getSimpleName())
                            .addProperty(wocHasModifier, visibility)
                            .addProperty(wocHasReturnType, method.getType().getQualifiedName())
                            .addProperty(exIsPatchOfMethod, model.createResource(stableMethodURI));

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

                    for (CtTypeReference<? extends Throwable> excRef : method.getThrownTypes()) {
                        patchedMethodRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                    }
                }
            }

            for (CtConstructor<?> constructor : type.getElements(new TypeFilter<>(CtConstructor.class))) {
                String visibility = constructor.isPublic() ? "public" : constructor.isProtected() ? "protected" : constructor.isPrivate() ? "private" : "package-private";
                String constructorSignature = buildConstructorSignature(constructor);
                String constructorURI = EX_NS + fqcn + "_" + constructorSignature + "_constructor";

                Resource constructorRes = model.createResource(constructorURI)
                        .addProperty(RDF.type, wocConstructorClass)
                        .addProperty(wocName, constructor.getSimpleName())
                        .addProperty(wocHasModifier, visibility);

                classResource.addProperty(wocHasConstructor, constructorRes);

                int index = 0;
                for (CtParameter<?> param : constructor.getParameters()) {
                    String paramURI = constructorURI + "_param_" + index;
                    Resource paramRes = model.createResource(paramURI)
                            .addProperty(RDF.type, wocParameterClass)
                            .addProperty(wocParamIndex, model.createTypedLiteral(index))
                            .addProperty(wocParamType, param.getType().getQualifiedName());

                    constructorRes.addProperty(wocHasParameter, paramRes);
                    index++;
                }

                for (CtTypeReference<? extends Throwable> excRef : constructor.getThrownTypes()) {
                    constructorRes.addProperty(wocThrowsException, excRef.getQualifiedName());
                }
            }
        }

        return model;
    }

    public static void main(String[] args) {
        Model model = buildModelForFile("/Users/tanguyro/Incodame/paragraph/ParagraphUI/src/main/java/org/incodame/paragraph/sample/webapp/demo/Parameter.java");

        try (FileOutputStream out = new FileOutputStream("refmodule-patch.ttl")) {
            model.write(out, "TURTLE");
            System.out.println("Fichier RDF 'refmodule-patch.ttl' généré avec succès !");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
