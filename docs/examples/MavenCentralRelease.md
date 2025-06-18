# Publishing to Maven Central - Complete Guide

## Overview
This guide walks you through publishing the Redis Stream component to Maven Central.

## Prerequisites Setup

### 1. Sonatype OSSRH Account
1. **Create account**: https://issues.sonatype.org/secure/Signup!default.jspa
2. **Create JIRA ticket** for new project:
   - Project: OSSRH
   - Issue Type: New Project  
   - Group Id: `tech.nelreina` (or `io.github.nelreina` if easier)
   - Project URL: https://github.com/nelreina/camel-quarkus-redis-stream
   - SCM URL: https://github.com/nelreina/camel-quarkus-redis-stream.git

### 2. Domain Verification
**Option A: Own domain (tech.nelreina)**
- Add TXT record to DNS: `_sonatype-challenge.nelreina.tech`
- Content provided by Sonatype in JIRA ticket

**Option B: GitHub-based (easier)**
- Change groupId to `io.github.nelreina`
- Update package names accordingly
- No domain verification needed

### 3. GPG Key Setup
```bash
# Generate key pair
gpg --gen-key
# Use: Nelson Reina <your-email@domain.com>

# Get key ID
gpg --list-secret-keys --keyid-format LONG

# Upload to key servers
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

### 4. Maven Settings
Copy `docs/examples/maven-settings.xml` to `~/.m2/settings.xml` and update:
- `YOUR_SONATYPE_USERNAME`
- `YOUR_SONATYPE_PASSWORD`  
- `YOUR_GPG_PASSPHRASE`

## Release Steps

### 1. Prepare Release Version
```bash
# Update version to release (remove SNAPSHOT)
mvn versions:set -DnewVersion=1.0.0

# Commit version change
git add pom.xml
git commit -m "Release version 1.0.0"
git tag v1.0.0
```

### 2. Deploy to Maven Central
```bash
# Clean build with all artifacts
mvn clean deploy -P release

# This will:
# - Compile and test
# - Generate sources JAR
# - Generate javadoc JAR  
# - Sign all artifacts with GPG
# - Upload to OSSRH staging
# - Auto-release to Maven Central (if autoReleaseAfterClose=true)
```

### 3. Verify Release
```bash
# Check staging repository (if autoReleaseAfterClose=false)
# Visit: https://s01.oss.sonatype.org/

# Search Maven Central (after ~30 minutes)
# Visit: https://search.maven.org/
# Search: g:tech.nelreina a:camel-quarkus-redis-stream
```

### 4. Prepare Next Development Version
```bash
# Update to next SNAPSHOT version
mvn versions:set -DnewVersion=1.0.1-SNAPSHOT

# Commit next version
git add pom.xml  
git commit -m "Prepare next development version 1.0.1-SNAPSHOT"
git push origin main --tags
```

## Release Profile
Add to POM for release builds:

```xml
<profiles>
    <profile>
        <id>release</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-gpg-plugin</artifactId>
                    <executions>
                        <execution>
                            <id>sign-artifacts</id>
                            <phase>verify</phase>
                            <goals>
                                <goal>sign</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

## Troubleshooting

### Common Issues

1. **GPG signing fails**
   ```bash
   # Test GPG signing
   echo "test" | gpg --clearsign
   
   # If fails, check GPG agent
   gpg-agent --daemon
   ```

2. **Upload fails with 401**
   - Check OSSRH credentials in settings.xml
   - Verify JIRA ticket is approved

3. **Group ID not allowed**
   - Domain verification incomplete
   - Consider using io.github.nelreina instead

4. **Artifacts don't appear**
   - Check https://s01.oss.sonatype.org/ staging area
   - Manual release may be needed
   - Can take 30+ minutes to sync to Central

### Validation Commands
```bash
# Test local deploy
mvn clean deploy -DaltDeploymentRepository=local::default::file:./target/staging-deploy

# Validate POM requirements
mvn help:effective-pom | grep -A5 -B5 "licenses\|developers\|scm"

# Test GPG signing
mvn clean verify -Dgpg.skip=false
```

## Post-Release

### Update Documentation
- [ ] Update README with Maven Central coordinates
- [ ] Create GitHub release with changelog
- [ ] Update version compatibility matrix
- [ ] Announce on relevant channels

### Maven Central Badge
Add to README:
```markdown
[![Maven Central](https://img.shields.io/maven-central/v/tech.nelreina/camel-quarkus-redis-stream.svg)](https://search.maven.org/artifact/tech.nelreina/camel-quarkus-redis-stream)
```

## Timeline
- **OSSRH ticket approval**: 1-2 business days
- **First release**: Manual verification required
- **Subsequent releases**: Automated with proper setup
- **Sync to Central**: 30 minutes to 2 hours

Once set up, releases become a simple `mvn clean deploy` command! 🚀