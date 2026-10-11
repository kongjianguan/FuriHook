# Browser fixtures

The Android WebView and Via fixtures use the React 18.3.1 and React DOM 18.3.1 UMD production builds from the official npm packages. The checked-in files under `vendor/` let the fixture run without network access. FuriHook's production APK does not include these test-only libraries.

Both packages use the MIT License. Their complete license texts are retained under `licenses/` and in the test application's Android assets.

The source package integrity values reported by the npm registry are:

- `react@18.3.1`: `sha512-wS+hAgJShR0KhEvPJArfuPVN1+Hz1t0Y6n5jLrGQbkb4urgPE/0Rve+1kMB1v/oWgHgm4WIcV+i7F2pTVj+2iQ==`
- `react-dom@18.3.1`: `sha512-5m4nQKp+rZRb09LNH59GM4BxTh9251/ylbKIbpe7TpGxfJ+9kv6BLkLBXIjjspbgbnIBNqlI23tRnTWT0snUIw==`
