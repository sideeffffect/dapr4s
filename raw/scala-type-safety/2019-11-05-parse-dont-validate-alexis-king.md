# Parse, Don't Validate

> Source: https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/
> Collected: 2026-05-02
> Published: 2019-11-05

## The Essence of Type-Driven Design

The core principle is captured in three words: **Parse, don't validate.** This encapsulates what type-driven design means—using the type system to preserve information about data rather than discarding it.

## The Realm of Possibility

Static type systems can determine whether certain functions are implementable. Consider this Haskell example:

```haskell
head :: [a] -> a
head (x:_) = x
```

The compiler rejects this with a warning about non-exhaustive patterns—specifically the empty list case `[]`. The function is *partial*, not defined for all inputs. Since an empty list has no first element, this function is genuinely impossible to implement completely.

## Turning Partial Functions Total

### Managing Expectations

One approach weakens the promise by returning `Maybe`:

```haskell
head :: [a] -> Maybe a
head (x:_) = Just x
head []    = Nothing
```

However, this creates friction at call sites. Even when callers have already verified a list is non-empty, they must still handle `Nothing`:

```haskell
getConfigurationDirectories :: IO [FilePath]
getConfigurationDirectories = do
  configDirsString <- getEnv "CONFIG_DIRS"
  let configDirsList = split ',' configDirsString
  when (null configDirsList) $
    throwIO $ userError "CONFIG_DIRS cannot be empty"
  pure configDirsList

main :: IO ()
main = do
  configDirs <- getConfigurationDirectories
  case head configDirs of
    Just cacheDir -> initializeCache cacheDir
    Nothing -> error "should never happen; already checked configDirs is non-empty"
```

This is problematic for three reasons:
1. It clutters code with redundant checks
2. It carries potential performance costs
3. Most critically, it's "a bug waiting to happen"—if `getConfigurationDirectories` changes, the redundant check won't catch the inconsistency

### Paying It Forward

Instead of weakening the return type, strengthen the argument type using `NonEmpty`:

```haskell
data NonEmpty a = a :| [a]

head :: NonEmpty a -> a
head (x:|_) = x
```

Now the function is total. Update the program accordingly:

```haskell
getConfigurationDirectories :: IO (NonEmpty FilePath)
getConfigurationDirectories = do
  configDirsString <- getEnv "CONFIG_DIRS"
  let configDirsList = split ',' configDirsString
  case nonEmpty configDirsList of
    Just nonEmptyConfigDirsList -> pure nonEmptyConfigDirsList
    Nothing -> throwIO $ userError "CONFIG_DIRS cannot be empty"

main :: IO ()
main = do
  configDirs <- getConfigurationDirectories
  initializeCache (head configDirs)
```

The redundant check vanishes. The validation happens exactly once where data enters the system, and the type preserves this knowledge throughout the program.

## The Power of Parsing

The distinction between validation and parsing lies in information preservation:

```haskell
validateNonEmpty :: [a] -> IO ()
validateNonEmpty (_:_) = pure ()
validateNonEmpty [] = throwIO $ userError "list cannot be empty"

parseNonEmpty :: [a] -> IO (NonEmpty a)
parseNonEmpty (x:xs) = pure (x:|xs)
parseNonEmpty [] = throwIO $ userError "list cannot be empty"
```

Both check the same condition, but `validateNonEmpty` discards the knowledge gained (returns `()`), while `parseNonEmpty` preserves it in the type system.

A parser consumes less-structured input and produces more-structured output. By this definition, `parseNonEmpty` is a genuine parser—it parses lists into non-empty lists. Real-world examples include:

- **aeson**: Parses JSON into domain types
- **optparse-applicative**: Parses command-line arguments
- **persistent/postgresql-simple**: Parses database values
- **servant**: Parses path components, query parameters, HTTP headers

These libraries sit at system boundaries where parsing is essential.

## The Danger of Validation

The language-theoretic security field identifies "*shotgun parsing*" as a serious antipattern. According to The Seven Turrets of Babel:

> "Shotgun parsing is a programming antipattern whereby parsing and validating code is mixed with and spread across processing code—throwing a cloud of checks at the input, and hoping one or another would catch all bad cases."

The consequences are severe:

> "Late-discovered errors in an input stream will result in some portion of invalid input having been processed, with the consequence that program state is difficult to predict."

Validation-based approaches make it impossible to determine whether everything was truly validated up front. Parsing avoids this by stratifying programs into two phases—parsing and execution—where failure due to invalid input can only occur during the first phase.

## Parsing in Practice

**Focus on datatypes.** When designing functions, ask: what data structure would eliminate the need for a check?

Instead of:
```haskell
checkNoDuplicateKeys :: (MonadError AppError m, Eq k) => [(k, v)] -> m ()
```

Use:
```haskell
checkNoDuplicateKeys :: (MonadError AppError m, Eq k) => [(k, v)] -> m (Map k v)
```

The return value becomes necessary for the program to proceed—the check cannot be accidentally omitted.

### Key Principles

1. **Use data structures that make illegal states unrepresentable.** Choose the most precise representation feasible.
2. **Push proof burdens upward but not beyond necessity.** Parse data into precise representations quickly, ideally at system boundaries before any processing occurs.

Write functions on the data representation you *wish* to have, not what you're given.

### Additional Guidance

- **Let datatypes inform code.** Avoid sticking arbitrary `Bool` fields in records just because a function needs them.
- **Suspect functions returning `m ()`**. If their primary purpose is raising errors, likely a better approach exists.
- **Parse in multiple passes if needed.** Context-sensitive parsing is acceptable as long as no processing occurs before parsing completes.
- **Avoid denormalized data, especially mutable.** Duplication creates easily-representable illegal states.
- **Use abstract datatypes to fake parsers from validators.** For cases where true unrepresentability is impractical (like integer ranges), employ abstract `newtype` with smart constructors.
