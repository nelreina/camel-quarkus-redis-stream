# Alternative: Publishing to GitHub Packages

If Maven Central setup seems complex, GitHub Packages is a simpler alternative for immediate distribution.

## Benefits of GitHub Packages
- ✅ **Easier setup**: No domain verification needed
- ✅ **Faster**: No approval process
- ✅ **Integrated**: With your existing GitHub repo
- ✅ **Good for early releases**: Beta testing and early adopters

## Setup

### 1. Add Distribution Management
```xml
<distributionManagement>
    <repository>
        <id>github</id>
        <name>GitHub nelreina Apache Maven Packages</name>
        <url>https://maven.pkg.github.com/nelreina/camel-quarkus-redis-stream</url>
    </repository>
</distributionManagement>
```

### 2. GitHub Token
1. Go to GitHub Settings → Developer settings → Personal access tokens
2. Generate token with `write:packages` scope
3. Add to `~/.m2/settings.xml`:

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

### 3. Publish
```bash
# Update to release version
mvn versions:set -DnewVersion=1.0.0

# Deploy to GitHub Packages
mvn clean deploy

# Users consume with:
```

### 4. Consumer Setup
Users need to add your repository:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/nelreina/camel-quarkus-redis-stream</url>
    </repository>
</repositories>
```

## Recommendation
1. **Start with GitHub Packages** for immediate distribution
2. **Move to Maven Central** when ready for wider adoption

GitHub Packages is perfect for getting your component out there quickly! 📦