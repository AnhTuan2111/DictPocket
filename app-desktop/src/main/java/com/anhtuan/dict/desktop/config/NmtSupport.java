package com.anhtuan.dict.desktop.config;

import com.anhtuan.dict.nmt.OnnxNmtEngine;

import java.nio.file.Path;
import java.util.Optional;

// Cổng duy nhất trong app-desktop biết đến module nmt-engine. Bản đóng gói thường không kèm ONNX
// Runtime (132 MB) nên các class đó có thể không tồn tại lúc chạy; Java ném NoClassDefFoundError
// (là Error, không phải Exception) khi chạm vào class vắng mặt. Bắt ở đây một lần để bên ngoài chỉ
// thấy Optional rỗng và tự ẩn tính năng.
public final class NmtSupport
{
    private NmtSupport()
    {
    }

    public static Path modelDir(Path dataDir)
    {
        return dataDir.resolve(OnnxNmtEngine.MODEL_DIR);
    }

    // Có đủ cả thư viện lẫn mô hình chưa. Không bao giờ ném lỗi.
    public static boolean isAvailable(Path dataDir)
    {
        try
        {
            return OnnxNmtEngine.isInstalled(modelDir(dataDir));
        }
        catch (NoClassDefFoundError | RuntimeException e)
        {
            return false;
        }
    }

    // Nạp mô hình mất vài trăm ms và ~300 MB RAM: chỉ gọi khi người dùng bật, và ngoài luồng giao diện
    public static Optional<OnnxNmtEngine> load(Path dataDir)
    {
        try
        {
            return Optional.of(OnnxNmtEngine.load(modelDir(dataDir)));
        }
        catch (NoClassDefFoundError | RuntimeException e)
        {
            return Optional.empty();
        }
    }
}
