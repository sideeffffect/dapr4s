# Testcontainers Dapr — Java Source and API Documentation

> Source: https://javadoc.io/doc/io.dapr/testcontainers-dapr/latest/index.html; https://github.com/diagridio/testcontainers-dapr
> Collected: 2026-05-01
> Published: Unknown

## Repository Overview (diagridio/testcontainers-dapr)

The Testcontainers Dapr Module allows you to set up Dapr for local development in your Java applications, providing by default an in-memory implementation of the Dapr APIs.

Maven dependency:
```xml
<dependency>
    <groupId>io.diagrid.dapr</groupId>
    <artifactId>testcontainers-dapr</artifactId>
    <version>0.10.x</version>
</dependency>
```

Note: The javadoc.io index page returned 403. Full API detail was obtained from the GitHub source at `diagridio/testcontainers-dapr` (main branch).

---

## Package: `io.diagrid.dapr`

### Class `DaprContainer`

```java
public class DaprContainer extends GenericContainer<DaprContainer>
```

Default image: `daprio/daprd`

**Constants:**
- `DAPRD_HTTP_PORT = 3500`
- `DAPRD_GRPC_PORT = 50001`

**Constructors:**
```java
DaprContainer(DockerImageName dockerImageName)
DaprContainer(String image)
```

**Builder methods (fluent):**
```java
DaprContainer withAppName(String appName)
DaprContainer withAppPort(Integer port)
DaprContainer withAppChannelAddress(String appChannelAddress)
DaprContainer withPlacementService(String placementService)
DaprContainer withDaprLogLevel(DaprLogLevel daprLogLevel)
DaprContainer withComponent(Component component)
DaprContainer withComponent(String name, String type, String version, List<MetadataEntry> metadataEntries)
DaprContainer withComponent(Path path)   // loads from YAML file
DaprContainer withSubscription(String name, String pubSubName, String pubSubTopic, String route)
DaprContainer withPlacementImage(String placementDockerImageName)
DaprContainer withReusablePlacement(boolean reuse)
DaprContainer withPlacementContainer(DaprPlacementContainer placementContainer)
```

**Accessor methods:**
```java
int getHttpPort()
String getHttpEndpoint()   // "http://<host>:<port>"
int getGrpcPort()
String getAppName()
Integer getAppPort()
String getAppChannelAddress()
String getPlacementService()
Set<Component> getComponents()
Set<Subscription> getSubscriptions()
static DockerImageName getDefaultImageName()
```

**Serialization helpers:**
```java
Map<String, Object> componentToMap(Component component)
Map<String, Object> subscriptionToMap(Subscription subscription)
String componentToYaml(Component component)
String subscriptionToYaml(Subscription subscription)
```

**configure() behavior (called at container start):**
1. If no network set, creates `Network.newNetwork()`.
2. If no `placementContainer` set, creates and starts a `DaprPlacementContainer` automatically.
3. Builds the `daprd` command with: `-app-id`, `--dapr-listen-addresses=0.0.0.0`, `--app-protocol http`, `-placement-host-address <placement>:50006`, `--app-channel-address`, `--app-port`, `--log-level`, `-components-path /components`.
4. If no components provided, adds default: `kvstore` (state.in-memory v1) and `pubsub` (pubsub.in-memory v1).
5. If no subscriptions provided and components exist, adds default: `local` subscription on `pubsub/topic → /events`.
6. Copies all component and subscription YAML into `/components/` inside the container.

**DaprLogLevel enum:**
```java
enum DaprLogLevel { error, warn, info, debug }
```

---

### Inner Class `DaprContainer.Component`

```java
public static class Component {
    Component(String name, String type, String version, Map<String, Object> metadata)
    Component(String name, String type, String version, List<MetadataEntry> metadataEntries)

    String getName()
    String getType()
    String getVersion()
    List<MetadataEntry> getMetadata()
}
```

---

### Inner Class `DaprContainer.Subscription`

```java
public static class Subscription {
    Subscription(String name, String pubsubName, String topic, String route)
    // Fields: name, pubsubName, topic, route
}
```

---

### Inner Class `DaprContainer.MetadataEntry`

```java
public static class MetadataEntry {
    MetadataEntry(String name, Object value)
    String getName()
    void setName(String name)
    Object getValue()
    void setValue(String value)
}
```

---

### Class `DaprPlacementContainer`

```java
public class DaprPlacementContainer extends GenericContainer<DaprPlacementContainer>
```

Default image: `daprio/placement`
Default port: `50006`

**Constructor:**
```java
DaprPlacementContainer(DockerImageName dockerImageName)
DaprPlacementContainer(String image)
```

**Builder methods:**
```java
DaprPlacementContainer withPort(Integer port)
```

**Accessor methods:**
```java
int getPort()
static DockerImageName getDefaultImageName()
```

**configure() behavior:** Runs `./placement -port <port>`

---

### Class `QuotedBoolean`

Helper for YAML serialization — wraps a boolean string value in quotes (e.g., `"true"`) for Dapr component metadata that requires quoted boolean strings.

---

## Test Examples (from DaprContainerTest.java)

### Basic container with WireMock app stub

```java
@ClassRule
public static DaprContainer daprContainer = new DaprContainer("daprio/daprd")
    .withAppName("dapr-app")
    .withAppPort(8081)
    .withAppChannelAddress("host.testcontainers.internal");
```

Note: `withAppChannelAddress("host.testcontainers.internal")` — required when the app runs on the host machine, not in Docker. This tells `daprd` how to reach the app channel.

### Exposing host ports:
```java
Testcontainers.exposeHostPorts(8081);
```

### Setting Dapr gRPC port as system property:
```java
System.setProperty("dapr.grpc.port", Integer.toString(daprContainer.getGrpcPort()));
```

---

## Component Examples (from DaprComponentTest.java)

### Component with QuotedBoolean metadata:
```java
new Component(
    "statestore",
    "state.in-memory",
    "v1",
    Collections.singletonMap("actorStateStore", new QuotedBoolean("true")))
```

Resulting YAML:
```yaml
metadata:
  name: statestore
apiVersion: dapr.io/v1alpha1
kind: Component
spec:
  metadata:
  - name: actorStateStore
    value: "true"
  type: state.in-memory
  version: v1
```

### Subscription serialization:
```java
.withSubscription("my-subscription", "pubsub", "topic", "/events")
```

Resulting YAML:
```yaml
metadata:
  name: my-subscription
apiVersion: dapr.io/v1alpha1
kind: Subscription
spec:
  route: /events
  pubsubname: pubsub
  topic: topic
```

---

## Key Design Notes

- `DaprContainer` uses `withAccessToHost(true)` so the container can reach the test application on the host.
- The container does NOT wait for `daprd` readiness by default (commented out in source). This is because `daprd` needs to connect back to the application for subscriptions, creating a chicken-and-egg problem.
- The Maven group ID in the diagridio repo is `io.diagrid.dapr` (not `io.dapr`). The io.dapr groupId is used by the main Dapr Java SDK BOM.
- Components are written as YAML files to `/components/` inside the container at startup time.
