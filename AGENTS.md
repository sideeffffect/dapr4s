We're creating a new research project. A library which uses Safe Scala to expose DAPR concepts/building blocks as Capabilities.
It uses these DAPR capabilities as a kind of "effect system".
Because it's Safe Scala, no other effects are possible. In effect, each DAPR effect is tracked by Scala's type system.

The project contains wiki using the karpathy-llm-wiki SKILL.
Use the it, add to it, improve it and maintain it.
The wiki contains information about Safe Scala, Capabilities, Effect Systems, DAPR, etc. If there's something missing, don't hesitate to search the web and add it to the wiki.

We'll use the DAPR Java SDK as the implementation, but the library won't expose anythig from DAPR Java SDK to the lirbary user. The library user will be exposed only Scala concepts, like case classes, sealed traits, traits, etc, wrapping all the ugly dirty side-effecful things inside from Java SDK.
The user of the library must be able to write Safe Scala to implement DAPR applications.

The project uses Scala CLI as the build tool.

