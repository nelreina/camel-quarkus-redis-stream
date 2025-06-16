# Dev UI Error Fix

## Issue
After fixing component discovery, users encountered a Quarkus Dev UI error:
```
java.lang.RuntimeException: Failed to locate 'artifact' or 'group-id' and 'artifact-id' among metadata keys
```

## Root Cause
The `quarkus-extension.yaml` file was causing conflicts with Quarkus Dev UI processing, trying to treat the component as a full Quarkus extension when it's actually just a Camel component library.

## Solution
**Removed `quarkus-extension.yaml`** - This file is not needed for Camel components since:

1. **Component discovery** works through standard Camel mechanisms:
   - `META-INF/services/org/apache/camel/component/redis-stream`
   - `@Component("redis-stream")` annotation
   - `@ApplicationScoped` for CDI integration

2. **Quarkus extension descriptor** is only needed for extensions that:
   - Add build-time processing
   - Provide dev services
   - Need custom Dev UI integration
   - Modify the Quarkus application structure

## Result
- ✅ **Component discovery** still works perfectly
- ✅ **No more Dev UI errors** during development
- ✅ **Cleaner component library** without unnecessary Quarkus extension complexity
- ✅ **Standard Camel component** that works in any Quarkus + Camel application

## Testing
The component now works cleanly with:
```bash
mvn quarkus:dev
```

No more runtime exceptions related to extension metadata processing.

## Key Insight
**Camel components != Quarkus extensions**
- Camel components use their own discovery mechanisms
- Only create `quarkus-extension.yaml` if you need actual Quarkus extension features
- For most Camel components, standard component registration is sufficient