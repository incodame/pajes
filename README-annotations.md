# Annotations for client projects

The annotations in Pajes' `org.incodame.pajes.annotations` package
(`EnumValue`, `ValueRange`, and `ApiWrapper`) are for test usage within Pajes
only. Client projects should not depend on or use these annotations.

Define project-owned annotations for the constraints your API needs. Configure
each constraint annotation in `pajes-config.yaml` under `client-annotations`,
listing the annotation name and the properties Pajes should include in the
generated client constraints. `ClientUseProcessor` matches configured
constraint annotations by qualified or simple name.

Client projects that use wrapper methods must also define their own
`ApiWrapper` annotation and apply it to those wrapper methods. Pajes recognizes
this marker by its exact simple name, `ApiWrapper`, regardless of its package.
The annotation is a source-analysis convention; it does not inline or otherwise
change Java code. For example:

```java
package com.example.client.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ApiWrapper {
}
```

```java
@ApiWrapper
void writeModel(@AllowedFormats({"TURTLE", "N3"}) String format) {
    model.write(format);
}
```

For Pajes to associate the wrapper's constraint with the underlying API method,
an `@ApiWrapper` method must contain exactly one call into the selected target
package. Annotated wrapper parameters must be passed directly to that API call
in the corresponding parameter positions. Pajes records the underlying API
method—not the wrapper—in RDF and uses the client-side source line where the
wrapper is called for the constraint identifier. Configured annotations on a
wrapper's return type are transferred to the underlying API method's return
constraint when the wrapper directly returns that API call's result.
