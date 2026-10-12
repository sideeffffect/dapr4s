# dapr-wasm-components WIT package dapr-wasm-components:interfaces@0.6.0

> Source: https://github.com/sideeffffect/dapr-wasm-components (commit afa33c51855e6fbf63f59dd191fc66fff293b7d0, components/wit/*.wit + README.md)
> Collected: 2026-10-12
> Published: 2026-10-12

Doc comments removed; signatures verbatim.

## actors-callback.wit

```wit
interface actors-callback {
  use types.{app-error};
  record actor-config {
    entities: list<string>,
    actor-idle-timeout: option<string>,
    actor-scan-interval: option<string>,
    drain-ongoing-call-timeout: option<string>,
    drain-rebalanced-actors: option<bool>,
    reentrancy-enabled: option<bool>,
    reentrancy-max-stack-depth: option<u32>,
    reminders-storage-partitions: option<u32>,
  }
  record actor-timer-event {
    actor-type: string,
    actor-id: string,
    name: string,
    due-time: option<string>,
    period: option<string>,
    data: list<u8>,
    callback: option<string>,
  }
  record actor-reminder-event {
    actor-type: string,
    actor-id: string,
    name: string,
    due-time: option<string>,
    period: option<string>,
    data: list<u8>,
  }
  config: func() -> actor-config;
  on-invoke: func(actor-type: string, actor-id: string, method: string, data: list<u8>) -> result<list<u8>, app-error>;
  on-timer: func(event: actor-timer-event) -> result<_, app-error>;
  on-reminder: func(event: actor-reminder-event) -> result<_, app-error>;
  deactivate: func(actor-type: string, actor-id: string) -> result<_, app-error>;
}
```

## actors.wit

```wit
interface actors {
  use types.{json};
  variant actors-error {
    permission-denied(string),
  }
  variant invoke-actor-error {
    actor-error(list<u8>),
    actors(actors-error),
  }
  variant get-state-error {
    key-not-found,
    actors(actors-error),
  }
  variant get-reminder-error {
    reminder-not-found,
    actors(actors-error),
  }
  enum actor-state-operation-type {
    upsert,
    delete,
  }
  record actor-state-operation {
    operation: actor-state-operation-type,
    key: string,
    value: option<list<u8>>,
  }
  record reminder {
    due-time: option<string>,
    period: option<string>,
    ttl: option<string>,
    data: option<json>,
  }
  record timer {
    due-time: option<string>,
    period: option<string>,
    ttl: option<string>,
    data: option<json>,
    callback: option<string>,
  }
  invoke: func(
    actor-type: string,
    actor-id: string,
    method: string,
    body: list<u8>,
    content-type: option<string>,
  ) -> result<list<u8>, invoke-actor-error>;
  get-state: func(actor-type: string, actor-id: string, key: string) -> result<list<u8>, get-state-error>;
  execute-state-transaction: func(
    actor-type: string,
    actor-id: string,
    operations: list<actor-state-operation>,
  ) -> result<_, actors-error>;
  register-reminder: func(actor-type: string, actor-id: string, name: string, reminder: reminder) -> result<_, actors-error>;
  get-reminder: func(actor-type: string, actor-id: string, name: string) -> result<json, get-reminder-error>;
  unregister-reminder: func(actor-type: string, actor-id: string, name: string) -> result<_, actors-error>;
  register-timer: func(actor-type: string, actor-id: string, name: string, timer: timer) -> result<_, actors-error>;
  unregister-timer: func(actor-type: string, actor-id: string, name: string) -> result<_, actors-error>;
}
```

## bindings-callback.wit

```wit
interface bindings-callback {
  use types.{metadata, app-error};
  use state.{state-item};
  record binding-event {
    name: string,
    data: list<u8>,
    metadata: metadata,
  }
  enum binding-concurrency {
    sequential,
    parallel,
  }
  record binding-event-response {
    store-name: option<string>,
    states: list<state-item>,
    to: list<string>,
    data: option<list<u8>>,
    concurrency: binding-concurrency,
  }
  list-input-bindings: func() -> list<string>;
  on-binding-event: func(event: binding-event) -> result<binding-event-response, app-error>;
}
```

## bindings.wit

```wit
interface bindings {
  use types.{metadata};
  record invoke-binding-response {
    data: list<u8>,
    metadata: metadata,
  }
  variant bindings-error {
    binding-not-found(string),
    permission-denied(string),
  }
  variant invoke-binding-error {
    operation-not-supported(string),
    bindings(bindings-error),
  }
  invoke-binding: func(
    name: string,
    operation: string,
    data: list<u8>,
    metadata: metadata,
  ) -> result<invoke-binding-response, invoke-binding-error>;
}
```

## configuration-callback.wit

```wit
interface configuration-callback {
  use configuration.{configuration-item};
  record configuration-update {
    store-name: string,
    id: string,
    items: list<tuple<string, configuration-item>>,
  }
  on-configuration-event: func(update: configuration-update);
}
```

## configuration.wit

```wit
interface configuration {
  use types.{metadata};
  record configuration-item {
    value: string,
    version: string,
    metadata: metadata,
  }
  variant configuration-error {
    store-not-found(string),
    permission-denied(string),
  }
  variant unsubscribe-error {
    no-such-subscription(string),
    configuration(configuration-error),
  }
  get: func(store-name: string, keys: list<string>, metadata: metadata) -> result<list<tuple<string, configuration-item>>, configuration-error>;
  subscribe: func(store-name: string, keys: list<string>, metadata: metadata) -> result<string, configuration-error>;
  unsubscribe: func(store-name: string, id: string) -> result<_, unsubscribe-error>;
}
```

## conversation.wit

```wit
interface conversation {
  use types.{json, metadata};
  variant conversation-error {
    component-not-found(string),
    permission-denied(string),
  }
  variant converse-error {
    context-not-found(string),
    content-filtered(string),
    invalid-request(string),
    conversation(conversation-error),
  }
  record content-part {
    text: string,
  }
  record tool-call-function {
    name: string,
    arguments: string,
  }
  record tool-call {
    id: option<string>,
    function: tool-call-function,
  }
  record participant-message {
    name: option<string>,
    content: list<content-part>,
  }
  record assistant-message {
    name: option<string>,
    content: list<content-part>,
    tool-calls: list<tool-call>,
  }
  record tool-message {
    tool-id: option<string>,
    name: string,
    content: list<content-part>,
  }
  variant message {
    developer(participant-message),
    system(participant-message),
    user(participant-message),
    assistant(assistant-message),
    tool(tool-message),
  }
  record conversation-input {
    messages: list<message>,
    scrub-pii: option<bool>,
  }
  record tool-function {
    name: string,
    description: option<string>,
    parameters: option<json>,
  }
  record tool {
    function: tool-function,
  }
  record conversation-options {
    context-id: option<string>,
    parameters: option<json>,
    metadata: metadata,
    scrub-pii: option<bool>,
    temperature: option<f64>,
    tools: list<tool>,
    tool-choice: option<string>,
    response-format: option<json>,
    prompt-cache-retention: option<string>,
  }
  record prompt-tokens-details {
    audio-tokens: u64,
    cached-tokens: u64,
  }
  record completion-tokens-details {
    accepted-prediction-tokens: u64,
    audio-tokens: u64,
    reasoning-tokens: u64,
    rejected-prediction-tokens: u64,
  }
  record usage {
    completion-tokens: u64,
    prompt-tokens: u64,
    total-tokens: u64,
    completion-tokens-details: option<completion-tokens-details>,
    prompt-tokens-details: option<prompt-tokens-details>,
  }
  record result-message {
    content: string,
    tool-calls: list<tool-call>,
  }
  record choice {
    finish-reason: string,
    index: s64,
    message: result-message,
  }
  record conversation-output {
    choices: list<choice>,
    model: option<string>,
    usage: option<usage>,
  }
  record conversation-response {
    outputs: list<conversation-output>,
    context-id: option<string>,
  }
  converse: func(
    component-name: string,
    inputs: list<conversation-input>,
    options: option<conversation-options>,
  ) -> result<conversation-response, converse-error>;
}
```

## crypto.wit

```wit
interface crypto {
  variant crypto-error {
    component-not-found(string),
    permission-denied(string),
  }
  variant encrypt-error {
    key-not-found(string),
    unsupported-algorithm(string),
    crypto(crypto-error),
  }
  variant decrypt-error {
    key-not-found(string),
    malformed-ciphertext(string),
    crypto(crypto-error),
  }
  record encrypt-options {
    key-name: string,
    key-wrap-algorithm: string,
    data-encryption-cipher: option<string>,
    omit-decryption-key-name: option<bool>,
    decryption-key-name: option<string>,
  }
  encrypt: func(component-name: string, data: list<u8>, options: encrypt-options) -> result<list<u8>, encrypt-error>;
  decrypt: func(component-name: string, data: list<u8>, key-name: option<string>) -> result<list<u8>, decrypt-error>;
  resource encryptor {
    constructor(component-name: string, options: encrypt-options);
    write: func(chunk: list<u8>) -> result<_, encrypt-error>;
    finish: static func(this: encryptor) -> result<crypto-output, encrypt-error>;
  }
  resource decryptor {
    constructor(component-name: string, key-name: option<string>);
    write: func(chunk: list<u8>) -> result<_, decrypt-error>;
    finish: static func(this: decryptor) -> result<crypto-output, decrypt-error>;
  }
  resource crypto-output {
    read: func(max-len: u64) -> option<list<u8>>;
  }
}
```

## health-callback.wit

```wit
interface health-callback {
  use types.{app-error};
  health-check: func() -> result<_, app-error>;
}
```

## invocation-callback.wit

```wit
interface invocation-callback {
  use types.{metadata};
  use invocation.{http-verb, http-response};
  record invoke-request {
    method: string,
    verb: http-verb,
    headers: metadata,
    query: option<string>,
    content-type: string,
    data: list<u8>,
  }
  on-invoke: func(request: invoke-request) -> http-response;
}
```

## invocation.wit

```wit
interface invocation {
  use types.{metadata};
  variant invoke-error {
    app-unreachable(string),
    permission-denied(string),
  }
  enum http-verb {
    get,
    post,
    put,
    delete,
    patch,
    head,
    options,
  }
  record http-response {
    status: u16,
    headers: metadata,
    body: list<u8>,
  }
  invoke: func(
    app-id: string,
    method-path: string,
    verb: http-verb,
    headers: metadata,
    query: option<string>,
    body: list<u8>,
  ) -> result<http-response, invoke-error>;
}
```

## jobs-callback.wit

```wit
interface jobs-callback {
  use types.{app-error};
  record job-event {
    name: string,
    data: list<u8>,
    content-type: option<string>,
  }
  on-job-event: func(event: job-event) -> result<_, app-error>;
}
```

## jobs.wit

```wit
interface jobs {
  use types.{json};
  variant jobs-error {
    permission-denied(string),
  }
  variant schedule-error {
    already-exists(string),
    invalid-schedule(string),
    jobs(jobs-error),
  }
  variant get-job-error {
    job-not-found,
    jobs(jobs-error),
  }
  variant job-failure-policy {
    drop,
    constant(job-failure-policy-constant),
  }
  record job-failure-policy-constant {
    max-retries: option<u32>,
    interval: option<string>,
  }
  record job {
    schedule: option<string>,
    repeats: option<u32>,
    due-time: option<string>,
    ttl: option<string>,
    data: option<json>,
    failure-policy: option<job-failure-policy>,
  }
  schedule: func(name: string, job: job, overwrite: bool) -> result<_, schedule-error>;
  get: func(name: string) -> result<job, get-job-error>;
  delete: func(name: string) -> result<_, jobs-error>;
}
```

## lock.wit

```wit
interface lock {
  variant lock-error {
    store-not-found(string),
    permission-denied(string),
  }
  variant try-lock-error {
    not-acquired,
    lock(lock-error),
  }
  variant unlock-error {
    lock-does-not-exist,
    lock-belongs-to-others,
    lock(lock-error),
  }
  try-lock: func(
    store-name: string,
    resource-id: string,
    lock-owner: string,
    expiry-in-seconds: u32,
  ) -> result<_, try-lock-error>;
  unlock: func(store-name: string, resource-id: string, lock-owner: string) -> result<_, unlock-error>;
}
```

## pubsub-callback.wit

```wit
interface pubsub-callback {
  use types.{metadata};
  record bulk-subscribe-config {
    enabled: bool,
    max-messages-count: option<s32>,
    max-await-duration-ms: option<s32>,
  }
  record topic-rule {
    match: string,
    path: string,
  }
  record topic-routes {
    rules: list<topic-rule>,
    default: option<string>,
  }
  record topic-subscription {
    pubsub-name: string,
    topic: string,
    metadata: metadata,
    dead-letter-topic: option<string>,
    routes: option<topic-routes>,
    bulk-subscribe: option<bulk-subscribe-config>,
  }
  record topic-event {
    id: string,
    source: string,
    %type: string,
    spec-version: string,
    data-content-type: option<string>,
    data: list<u8>,
    topic: string,
    pubsub-name: string,
    path: option<string>,
    extensions: metadata,
  }
  enum topic-event-response {
    success,
    retry,
    drop,
  }
  variant bulk-event {
    cloud-event(topic-event),
    raw(list<u8>),
  }
  record bulk-topic-event-entry {
    entry-id: string,
    event: bulk-event,
    content-type: option<string>,
    metadata: metadata,
  }
  record bulk-topic-event {
    id: string,
    entries: list<bulk-topic-event-entry>,
    metadata: metadata,
    topic: string,
    pubsub-name: string,
    %type: string,
    path: option<string>,
  }
  record bulk-topic-event-entry-response {
    entry-id: string,
    status: topic-event-response,
  }
  list-topic-subscriptions: func() -> list<topic-subscription>;
  on-topic-event: func(event: topic-event) -> topic-event-response;
  on-bulk-topic-event: func(event: bulk-topic-event) -> list<bulk-topic-event-entry-response>;
}
```

## pubsub.wit

```wit
interface pubsub {
  use types.{metadata};
  variant pubsub-error {
    component-not-found(string),
    permission-denied(string),
  }
  variant publish-bulk-error {
    not-supported,
    pubsub(pubsub-error),
  }
  record bulk-publish-entry {
    entry-id: string,
    event: list<u8>,
    content-type: string,
    metadata: metadata,
  }
  record bulk-publish-failed-entry {
    entry-id: string,
    error: string,
  }
  publish: func(
    pubsub-name: string,
    topic: string,
    data: list<u8>,
    data-content-type: option<string>,
    metadata: metadata,
  ) -> result<_, pubsub-error>;
  publish-bulk: func(
    pubsub-name: string,
    topic: string,
    entries: list<bulk-publish-entry>,
    metadata: metadata,
  ) -> result<list<bulk-publish-failed-entry>, publish-bulk-error>;
}
```

## runtime.wit

```wit
interface runtime {
  use types.{json};
  variant runtime-error {
    permission-denied(string),
  }
  get-metadata: func() -> result<json, runtime-error>;
  set-metadata-label: func(key: string, value: string) -> result<_, runtime-error>;
  healthz: func() -> bool;
  outbound-healthz: func() -> bool;
}
```

## secrets.wit

```wit
interface secrets {
  use types.{metadata};
  type secret = list<tuple<string, string>>;
  variant secrets-error {
    store-not-found(string),
    permission-denied(string),
  }
  variant get-secret-error {
    secret-not-found,
    secrets(secrets-error),
  }
  get-secret: func(store-name: string, key: string, metadata: metadata) -> result<secret, get-secret-error>;
  get-bulk-secret: func(store-name: string, metadata: metadata) -> result<list<tuple<string, secret>>, secrets-error>;
}
```

## state.wit

```wit
interface state {
  use types.{metadata, json};
  variant state-error {
    store-not-found(string),
    permission-denied(string),
  }
  variant write-error {
    etag-mismatch(option<string>),
    state(state-error),
  }
  variant transaction-error {
    etag-mismatch(option<string>),
    not-transactional,
    state(state-error),
  }
  variant get-error {
    key-not-found,
    state(state-error),
  }
  variant query-error {
    not-queryable,
    invalid-query(string),
    state(state-error),
  }
  enum concurrency {
    unspecified,
    first-write,
    last-write,
  }
  enum consistency {
    unspecified,
    eventual,
    strong,
  }
  record state-options {
    concurrency: concurrency,
    consistency: consistency,
  }
  record state-item {
    key: string,
    value: list<u8>,
    etag: option<string>,
    metadata: metadata,
    options: option<state-options>,
  }
  record get-state-response {
    data: list<u8>,
    etag: option<string>,
  }
  record bulk-state-item {
    key: string,
    value: list<u8>,
    etag: option<string>,
    error: option<string>,
  }
  record query-state-item {
    key: string,
    data: list<u8>,
    etag: option<string>,
  }
  enum transaction-operation {
    upsert,
    delete,
  }
  record transaction-request {
    operation: transaction-operation,
    key: string,
    value: option<list<u8>>,
    etag: option<string>,
    metadata: metadata,
    options: option<state-options>,
  }
  record query-response {
    items: list<query-state-item>,
    token: option<string>,
  }
  get: func(
    store-name: string,
    key: string,
    consistency: option<consistency>,
    metadata: metadata,
  ) -> result<get-state-response, get-error>;
  get-bulk: func(
    store-name: string,
    keys: list<string>,
    parallelism: option<u32>,
    metadata: metadata,
  ) -> result<list<bulk-state-item>, state-error>;
  save: func(store-name: string, items: list<state-item>, metadata: metadata) -> result<_, write-error>;
  delete: func(
    store-name: string,
    key: string,
    etag: option<string>,
    options: option<state-options>,
    metadata: metadata,
  ) -> result<_, write-error>;
  execute-transaction: func(
    store-name: string,
    operations: list<transaction-request>,
    metadata: metadata,
  ) -> result<_, transaction-error>;
  query: func(store-name: string, query: json, metadata: metadata) -> result<query-response, query-error>;
}
```

## types.wit

```wit
package dapr-wasm-components:interfaces@0.6.0;
interface types {
  type metadata = list<tuple<string, string>>;
  type json = string;
  record app-error {
    message: string,
  }
}
```

## workflow.wit

```wit
interface workflow {
  variant workflow-error {
    component-not-found(string),
    permission-denied(string),
  }
  variant start-error {
    instance-already-exists(string),
    workflow(workflow-error),
  }
  variant instance-error {
    not-found(string),
    invalid-state(string),
    workflow(workflow-error),
  }
  variant get-error {
    not-found(string),
    workflow(workflow-error),
  }
  record workflow-instance {
    instance-id: string,
    created-at: string,
    last-updated-at: string,
    runtime-status: string,
    properties: list<tuple<string, string>>,
  }
  start: func(
    workflow-component: string,
    workflow-name: string,
    instance-id: option<string>,
    input: list<u8>,
  ) -> result<string, start-error>;
  get: func(workflow-component: string, instance-id: string) -> result<workflow-instance, get-error>;
  terminate: func(workflow-component: string, instance-id: string) -> result<_, instance-error>;
  raise-event: func(
    workflow-component: string,
    instance-id: string,
    event-name: string,
    event-data: list<u8>,
  ) -> result<_, instance-error>;
  pause: func(workflow-component: string, instance-id: string) -> result<_, instance-error>;
  resume: func(workflow-component: string, instance-id: string) -> result<_, instance-error>;
  purge: func(workflow-component: string, instance-id: string) -> result<_, instance-error>;
}
```

## world.wit

```wit
world app {
  // outbound — call Dapr
  import types;
  import state;
  import pubsub;
  import secrets;
  import bindings;
  import invocation;
  import configuration;
  import lock;
  import workflow;
  import jobs;
  import crypto;
  import conversation;
  import actors;
  import runtime;
  // inbound — be called by Dapr
  export invocation-callback;
  export pubsub-callback;
  export bindings-callback;
  export jobs-callback;
  export actors-callback;
  export configuration-callback;
  export health-callback;
}
world outbound {
  export types;
  export state;
  export pubsub;
  export secrets;
  export bindings;
  export invocation;
  export configuration;
  export lock;
  export workflow;
  export jobs;
  export crypto;
  export conversation;
  export actors;
  export runtime;
}
world inbound {
  import invocation-callback;
  import pubsub-callback;
  import bindings-callback;
  import jobs-callback;
  import actors-callback;
  import configuration-callback;
  import health-callback;
}
```

