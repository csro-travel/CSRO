"""The supported stateful fixture launcher fixes the HTTP bind to loopback."""
import os

import uvicorn


def main():
    if os.environ.get("MOCK_STATEFUL_ENABLED") != "true":
        raise SystemExit("Set MOCK_STATEFUL_ENABLED=true explicitly before starting this isolated fixture")
    from .main import app
    uvicorn.run(app, host="127.0.0.1", port=8001, proxy_headers=False)


if __name__ == "__main__":
    main()
