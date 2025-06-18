# Publishing to GitHub Packages

## Setup for Publishing

### 1. GitHub Personal Access Token
1. Go to GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token with these scopes:
   - `write:packages` (to publish packages)
   - `read:packages` (to download packages)
3. Copy the token

### 2. Configure Maven Settings
Add to your `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>nelreina</username>
            <password>YOUR_GITHUB_TOKEN</password>
        </server>
    </servers>
</settings>
```

### 3. Publish to GitHub Packages
```bash
# Build and deploy
mvn clean deploy

# This will publish to:
# https://github.com/nelreina/camel-quarkus-redis-stream/packages
```

## For Consumers

### Add Repository
Users need to add your GitHub Packages repository to their `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/nelreina/camel-quarkus-redis-stream</url>
    </repository>
</repositories>
```

### Add Dependency
```xml
<dependency>
    <groupId>tech.nelreina</groupId>
    <artifactId>camel-quarkus-redis-stream</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Consumer Maven Settings
Users also need GitHub token in their `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>THEIR_GITHUB_USERNAME</username>
            <password>THEIR_GITHUB_TOKEN</password>
        </server>
    </servers>
</settings>
```

## Benefits
- ✅ **No approval process**: Immediate publishing
- ✅ **Integrated with GitHub**: Same credentials and permissions
- ✅ **Version management**: Automatic versioning and releases
- ✅ **Security**: Uses GitHub authentication

## Limitations
- ❌ **Requires GitHub token**: Users need to configure authentication
- ❌ **Less discoverable**: Not in Maven Central search
- ❌ **GitHub dependency**: Tied to GitHub ecosystem

## Publishing Commands

```bash
# Release version 1.0.0 (current)
mvn clean deploy

# For next version, update pom.xml version and deploy again
mvn versions:set -DnewVersion=1.0.1
mvn clean deploy
```

The package will be available at:
https://github.com/nelreina/camel-quarkus-redis-stream/packages