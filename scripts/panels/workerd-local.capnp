using Workerd = import "/workerd/workerd.capnp";

# Local, real Workers runtime for the automatic UI test. Same engine Cloudflare
# runs (workerd), same artifact the app uploads (dist-panel/catpanel.obf.js), same
# metadata shape the uploader sends (main_module + compat date + nodejs_compat).
# No KV is bound on purpose: this is a login/verify/UI test, and the panel says so
# loudly when a write would not survive a restart.
const config :Workerd.Config = (
  services = [ (name = "main", worker = (
      modules = [ (name = "worker.js", esModule = embed "../../dist-panel/catpanel.obf.js" ) ],
      compatibilityDate = "2025-03-04",
      compatibilityFlags = ["nodejs_compat"],
      bindings = [
        (name = "OPEN_SUB", text = "true"),
        (name = "UUID", text = "11111111-2222-3333-4444-555555555555" ),
      ],
      ) ) ],
  sockets = [ (name = "http", address = "127.0.0.1:8799", http = (), service = "main") ],
);
