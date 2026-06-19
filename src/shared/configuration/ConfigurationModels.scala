package dapr4s.configuration

import dapr4s.*

import language.experimental.safe

/** A single configuration item returned by [[ConfigurationCapability.get]] or delivered via subscription.
  *
  * @param key
  *   The [[ConfigurationKey]] identifying this item.
  * @param value
  *   The current configuration value.
  * @param version
  *   The store-assigned version token (empty string if the store does not support versioning).
  * @param metadata
  *   Additional key-value metadata attached to the item by the configuration store.
  */
final case class ConfigurationItem(
    key: ConfigurationKey,
    value: ConfigurationValue,
    version: ConfigurationVersion,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty,
)

/** Represents a configuration update notification. */
final case class ConfigurationUpdate(storeName: ConfigurationStoreName, items: Map[ConfigurationKey, ConfigurationItem])
