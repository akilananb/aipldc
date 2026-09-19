# Local stack on Colima's k3s. Replaces infra/docker-compose.yml. See scripts/demo.sh for the
# scripted up/reset path (tilt ci / tilt down --delete-namespaces).
NS = 'pdlc'
RUNTIME_DIR = '/Users/work/Documents/restaurant-runtime'

if k8s_context() != 'colima':
    fail('Tiltfile expects kube context "colima" (run: colima start --kubernetes); current: ' + k8s_context())
if not os.path.exists(RUNTIME_DIR):
    fail(RUNTIME_DIR + ' missing; run scripts/demo.sh up (clones it) or create it first')

# --- infra/.env (optional, gitignored). Shell env wins over the file, then the file, then defaults —
# same precedence compose gave ${VAR:-default}.
def load_dotenv(path):
    out = {}
    for line in str(read_file(path, default='')).splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        k, v = line.split('=', 1)
        out[k.strip()] = v.strip()
    return out

dotenv = load_dotenv('infra/.env')

def env_or(key, default=''):
    return os.getenv(key) or dotenv.get(key) or default

langfuse_enabled = env_or('LANGFUSE_ENABLED', 'false').lower() == 'true'

# --- Secret: LLM gateway creds, build-worker token, demo flag, Langfuse keys. Blank Langfuse
# values (disabled) keep AgentsApplication.applyLangfuseFromEnv / AgentRunTracer.traceUrl() in
# their off/null branches; enabled defaults let LANGFUSE_ENABLED=true alone work end to end, and
# feed both `agents` and `langfuse-web`'s LANGFUSE_INIT_* so they cannot diverge.
k8s_yaml(encode_yaml({
    'apiVersion': 'v1',
    'kind': 'Secret',
    'type': 'Opaque',
    'metadata': {'name': 'pdlc-env', 'namespace': NS},
    'stringData': {
        'PDLC_LLM_BASE_URL': env_or('PDLC_LLM_BASE_URL', ''),
        'PDLC_LLM_API_KEY': env_or('PDLC_LLM_API_KEY', ''),
        'BUILD_AGENT_TOKEN': env_or('BUILD_AGENT_TOKEN', ''),
        'PDLC_DEMO_ENABLED': env_or('PDLC_DEMO_ENABLED', 'true'),
        'LANGFUSE_PUBLIC_KEY': env_or('LANGFUSE_PUBLIC_KEY', 'pk-lf-pdlc-local' if langfuse_enabled else ''),
        'LANGFUSE_SECRET_KEY': env_or('LANGFUSE_SECRET_KEY', 'sk-lf-pdlc-local' if langfuse_enabled else ''),
        'LANGFUSE_PUBLIC_URL': env_or('LANGFUSE_PUBLIC_URL', 'http://localhost:3000' if langfuse_enabled else ''),
        'LANGFUSE_PROJECT_ID': env_or('LANGFUSE_PROJECT_ID', 'pdlc-pilot' if langfuse_enabled else ''),
    },
}))

# --- ConfigMap: the runtime pdlc.yaml, mounted verbatim by control-plane/agents at /app/pdlc.yaml.
k8s_yaml(encode_yaml({
    'apiVersion': 'v1',
    'kind': 'ConfigMap',
    'metadata': {'name': 'pdlc-config', 'namespace': NS},
    'data': {'pdlc.yaml': str(read_file('infra/pdlc.yaml'))},
}))

# --- ConfigMap: stub-llm WireMock mappings, one key per mapping file.
stub_mappings_dir = 'infra/stub-llm/mappings'
stub_mapping_names = [os.path.basename(f) for f in listdir(stub_mappings_dir)]
stub_mappings_data = {}
for f in listdir(stub_mappings_dir):
    stub_mappings_data[os.path.basename(f)] = str(read_file(f))

k8s_yaml(encode_yaml({
    'apiVersion': 'v1',
    'kind': 'ConfigMap',
    'metadata': {'name': 'stub-llm-mappings', 'namespace': NS},
    'data': stub_mappings_data,
}))

# --- Restart-on-config-change. Pods only read pdlc.yaml/env at startup and Kubernetes does not
# restart pods on ConfigMap/Secret edits, so stamp a content hash onto the pod template; editing
# any watched input changes the annotation and triggers a rollout.
config_hash = str(local('cat infra/pdlc.yaml infra/.env 2>/dev/null | shasum -a 256 | cut -c1-16', quiet=True, echo_off=True)).strip()
stub_hash = str(local('cat infra/stub-llm/mappings/*.json | shasum -a 256 | cut -c1-16', quiet=True, echo_off=True)).strip()

def stamped(path, hash):
    objs = decode_yaml_stream(read_file(path))
    for o in objs:
        if o.get('kind') == 'Deployment':
            o['spec']['template']['metadata'].setdefault('annotations', {})['pdlc.dev/config-hash'] = hash
    return encode_yaml_stream(objs)

# stub-llm needs one subPath mount per mapping file (see infra/k8s/stub-llm.yaml comment): a
# whole-directory ConfigMap mount is a ..data symlink farm that WireMock 3.x's recursive
# mapping-directory scan walks 3x, tripling every stub.
def stub_llm_yaml(path, hash, filenames):
    objs = decode_yaml_stream(read_file(path))
    for o in objs:
        if o.get('kind') == 'Deployment':
            o['spec']['template']['metadata'].setdefault('annotations', {})['pdlc.dev/config-hash'] = hash
            for c in o['spec']['template']['spec']['containers']:
                if c.get('name') == 'stub-llm':
                    c['volumeMounts'] = [
                        {'name': 'mappings', 'mountPath': '/home/wiremock/mappings/' + f, 'subPath': f, 'readOnly': True}
                        for f in filenames
                    ]
    return encode_yaml_stream(objs)

k8s_yaml('infra/k8s/namespace.yaml')
k8s_yaml('infra/k8s/storage.yaml')
k8s_yaml('infra/k8s/temporal.yaml')
k8s_yaml('infra/k8s/postgres.yaml')
k8s_yaml(stub_llm_yaml('infra/k8s/stub-llm.yaml', stub_hash, stub_mapping_names))
k8s_yaml(stamped('infra/k8s/control-plane.yaml', config_hash))
k8s_yaml(stamped('infra/k8s/agents.yaml', config_hash))
k8s_yaml('infra/k8s/ui.yaml')

# --- Images. `only` lists are exactly the paths each Dockerfile COPYs; ignoring **/target and
# **/src/test stops `mvn test` runs (and test-only edits) from triggering rebuilds, since the
# images build with -DskipTests. No .dockerignore files are added: only/ignore already trim both
# the watch set and the build context (this also keeps the ui build from copying host
# node_modules over the container's npm ci output).
docker_build('pdlc-pilot/control-plane', '.', dockerfile='infra/restaurant/control-plane.Dockerfile',
    only=['pom.xml', 'core', 'adapters', 'control-plane', 'agents/pom.xml'],
    ignore=['**/target', '**/src/test'])
docker_build('pdlc-pilot/agents', '.', dockerfile='agents/Dockerfile',
    only=['pom.xml', 'core', 'adapters', 'agents', 'control-plane/pom.xml', '.agents/skills'],
    ignore=['**/target', '**/src/test'])
docker_build('pdlc-pilot/ui', 'ui', build_args={'VITE_API_BASE_URL': 'http://localhost:8081'},
    ignore=['node_modules', 'dist'])

# --- Resources. Explicit deps replace compose `depends_on` ordering in the Tilt UI; init
# containers still guard readiness at the pod level. No port_forwards anywhere: host ports come
# from the LoadBalancer Services that Colima forwards to localhost automatically; a Tilt forward
# on the same port would collide with Colima's forward (see plan contingency if that's ever not
# true on a given Colima version — switch Services to ClusterIP and add port_forwards there).
k8s_resource(new_name='namespace', objects=['pdlc:namespace'], labels=['infra'])
k8s_resource('temporal-postgres', resource_deps=['namespace'], objects=['temporal-postgres-data:persistentvolumeclaim'], labels=['infra'])
k8s_resource('temporal', resource_deps=['temporal-postgres'], labels=['infra'])
k8s_resource('temporal-ui', resource_deps=['temporal'], links=['http://localhost:8080'], labels=['infra'])
k8s_resource('postgres', resource_deps=['namespace'], objects=['postgres-data:persistentvolumeclaim'], labels=['infra'])
k8s_resource('stub-llm', resource_deps=['namespace'], objects=['stub-llm-mappings:configmap'], links=['http://localhost:4000/__admin/mappings'], labels=['infra'])
k8s_resource('control-plane', resource_deps=['postgres', 'temporal'], objects=['pdlc-config:configmap', 'pdlc-env:secret'], links=['http://localhost:8081/api/demo'], labels=['app'])
k8s_resource('agents', resource_deps=['postgres', 'temporal', 'stub-llm', 'control-plane'], links=['http://localhost:8082'], labels=['app'])
k8s_resource('ui', resource_deps=['control-plane'], links=['http://localhost:5173'], labels=['app'])

if langfuse_enabled:
    k8s_yaml('infra/k8s/langfuse.yaml')
    k8s_resource('langfuse-postgres', resource_deps=['namespace'], objects=['langfuse-env:configmap', 'langfuse-postgres-data:persistentvolumeclaim'], labels=['langfuse'])
    k8s_resource('langfuse-clickhouse', resource_deps=['namespace'], objects=['langfuse-clickhouse-data:persistentvolumeclaim'], labels=['langfuse'])
    k8s_resource('langfuse-minio', resource_deps=['namespace'], objects=['langfuse-minio-data:persistentvolumeclaim'], labels=['langfuse'])
    k8s_resource('langfuse-redis', resource_deps=['namespace'], labels=['langfuse'])
    k8s_resource('langfuse-worker', resource_deps=['langfuse-postgres', 'langfuse-clickhouse', 'langfuse-minio', 'langfuse-redis'], labels=['langfuse'])
    k8s_resource('langfuse-web', resource_deps=['langfuse-postgres', 'langfuse-clickhouse', 'langfuse-minio', 'langfuse-redis'], links=['http://localhost:3000'], labels=['langfuse'])
