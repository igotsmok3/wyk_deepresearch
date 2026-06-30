# MinerU 部署指南（Windows + WSL2）

> 目标：在 Windows 台式机（RTX 5060Ti）上通过 WSL2 运行 Ubuntu，部署 MinerU GPU 版本，并通过局域网提供 API 服务供 Mac 调用。
>
> 选择 WSL2 而非原生 Windows 的原因：CUDA 驱动在 Linux 环境下更稳定，pip 包兼容性更好，且 MinerU 官方主要在 Linux 下测试。

---

## 一、安装 WSL2 + Ubuntu

### 1. 开启 WSL2

以管理员身份打开 PowerShell：

```powershell
wsl --install
```

该命令会自动启用 WSL2 并安装 Ubuntu（默认 22.04）。安装完成后**重启电脑**。

重启后 Ubuntu 会自动弹出，按提示设置用户名和密码。

### 2. 验证 WSL2 版本

```powershell
wsl -l -v
```

确认 VERSION 列显示 `2`。

---

## 二、安装 NVIDIA 驱动（Windows 侧）

WSL2 的 CUDA 支持依赖 **Windows 侧的 NVIDIA 驱动**，无需在 WSL 内单独安装驱动。

前往 [NVIDIA 驱动下载页](https://www.nvidia.com/Download/index.aspx) 安装最新 Game Ready 或 Studio 驱动（>= 535 版本支持 CUDA 12.x）。

安装完成后，在 WSL 终端内验证：

```bash
nvidia-smi
```

能看到 GPU 信息和 CUDA Version 即表示 WSL2 GPU 穿透正常。

---

## 三、在 WSL2 中安装 CUDA Toolkit

> 注意：只需安装 CUDA Toolkit，**不要**在 WSL2 内安装 NVIDIA 驱动。

```bash
# 添加 CUDA 仓库（以 CUDA 12.4 为例）
wget https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/x86_64/cuda-keyring_1.1-1_all.deb
sudo dpkg -i cuda-keyring_1.1-1_all.deb
sudo apt-get update
sudo apt-get install -y cuda-toolkit-12-4
```

添加环境变量到 `~/.bashrc`：

```bash
echo 'export PATH=/usr/local/cuda/bin:$PATH' >> ~/.bashrc
echo 'export LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH' >> ~/.bashrc
source ~/.bashrc
```

验证：

```bash
nvcc --version
```

---

## 四、安装 Miniconda

```bash
wget https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
bash Miniconda3-latest-Linux-x86_64.sh
source ~/.bashrc
```

创建独立环境：

```bash
conda create -n mineru python=3.10 -y
conda activate mineru
```

---

## 五、安装 MinerU

### 1. 安装 PyTorch（CUDA 12.4）

```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu124
```

验证 GPU：

```bash
python -c "import torch; print(torch.cuda.is_available())"
# 输出 True 则正常
```

### 2. 安装 MinerU

```bash
pip install magic-pdf[full] --extra-index-url https://wheels.myhloli.com
```

### 3. 下载模型权重

```bash
pip install huggingface_hub
```

如果网络较慢，先设置镜像：

```bash
export HF_ENDPOINT=https://hf-mirror.com
```

然后下载：

```bash
python -c "from magic_pdf.model.doc_analyze_by_custom_model import download_json_and_models; download_json_and_models()"
```

### 4. 验证安装

```bash
magic-pdf --version
```

---

## 六、配置 MinerU

首次运行后会在 `~/.magic-pdf.json` 生成配置文件，确认 `device-mode` 为 `cuda`：

```json
{
  "device-mode": "cuda"
}
```

如果文件不存在，手动创建：

```bash
echo '{"device-mode": "cuda"}' > ~/.magic-pdf.json
```

---

## 七、启动 API 服务

### 安装 API 依赖

```bash
pip install fastapi uvicorn python-multipart
```

### 启动服务（监听所有网卡）

```bash
python -m magic_pdf.api.http_api --host 0.0.0.0 --port 8888
```

启动后访问 `http://localhost:8888/docs` 验证 Swagger 文档可访问。

### 后台持久运行（可选）

```bash
nohup python -m magic_pdf.api.http_api --host 0.0.0.0 --port 8888 > ~/mineru.log 2>&1 &
```

---

## 八、局域网访问配置

### 固定台式机 IP

在 Windows「网络设置」中将以太网/WLAN 设为静态 IP，例如 `192.168.1.100`，或在路由器中绑定 MAC 地址。

### WSL2 端口转发到 Windows

WSL2 有独立的内部网络，需将 WSL2 端口转发到 Windows，才能让局域网设备访问。

以**管理员身份**在 PowerShell 中执行：

```powershell
# 查看 WSL2 当前 IP
wsl hostname -I

# 设置端口转发（将 <WSL_IP> 替换为上面查到的 IP）
netsh interface portproxy add v4tov4 listenport=8888 listenaddress=0.0.0.0 connectport=8888 connectaddress=<WSL_IP>

# 开放防火墙
New-NetFirewallRule -DisplayName "MinerU API" -Direction Inbound -Protocol TCP -LocalPort 8888 -Action Allow
```

> WSL2 的内部 IP 每次重启可能变化，建议将上述命令保存为 `.ps1` 脚本，开机时以管理员运行一次。

### 自动化脚本（保存为 `wsl-portproxy.ps1`）

```powershell
$wslIp = (wsl hostname -I).Trim().Split(" ")[0]
netsh interface portproxy delete v4tov4 listenport=8888 listenaddress=0.0.0.0
netsh interface portproxy add v4tov4 listenport=8888 listenaddress=0.0.0.0 connectport=8888 connectaddress=$wslIp
Write-Host "Port proxy set to WSL2 IP: $wslIp"
```

---

## 九、Mac 端调用

台式机 IP 为 `192.168.1.100`，在 Mac 上测试连通性：

```bash
curl http://192.168.1.100:8888/docs
```

Python 调用示例：

```python
import requests

with open("document.pdf", "rb") as f:
    response = requests.post(
        "http://192.168.1.100:8888/pdf/parse",
        files={"file": f}
    )

print(response.json())
```

---

## 常见问题

| 问题 | 解决方式 |
|------|---------|
| WSL2 中 `nvidia-smi` 报错 | 确认 Windows 侧 NVIDIA 驱动已更新到最新版，WSL2 无需单独装驱动 |
| `torch.cuda.is_available()` 返回 False | 核查 PyTorch 版本与 CUDA Toolkit 版本是否匹配 |
| Mac 无法访问 8888 端口 | 重新执行端口转发脚本，并确认防火墙规则存在 |
| 模型下载超时 | 设置 `export HF_ENDPOINT=https://hf-mirror.com` 后重试 |
| WSL2 重启后端口转发失效 | 每次重启 Windows 后重新运行 `wsl-portproxy.ps1` |
