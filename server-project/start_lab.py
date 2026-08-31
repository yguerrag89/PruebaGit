import os
import threading
import webbrowser
import uvicorn

if __name__ == "__main__":
    port = int(os.environ.get("ILUBOX_LAB_PORT", "8876"))
    address = f"http://127.0.0.1:{port}"
    os.environ["ILUBOX_LAB_ORIGIN"] = address
    threading.Timer(1.2, lambda: webbrowser.open(address)).start()
    uvicorn.run("lab:create_lab_app", factory=True, host="127.0.0.1", port=port,
                proxy_headers=False, server_header=False, limit_concurrency=16)
