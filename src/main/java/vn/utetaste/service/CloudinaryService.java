package vn.utetaste.service;
import java.io.*;
import javax.imageio.ImageIO;
import java.net.URI;
import java.util.Set;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.DishRepository;
import vn.utetaste.security.Actor;
@Service
public class CloudinaryService {
    private final Cloudinary client;private final boolean configured;private final DishRepository dishes;private final OrderService ops;
    public CloudinaryService(Cloudinary client,@Value("${app.cloudinary.cloud-name}") String name,@Value("${app.cloudinary.api-key}") String key,@Value("${app.cloudinary.api-secret}") String secret,DishRepository dishes,OrderService ops) {
        configured=!name.isBlank()&&!key.isBlank()&&!secret.isBlank();this.dishes=dishes;this.ops=ops;
        this.client=client;
    }
    public boolean isConfigured() {return configured;}
    @Transactional public void upload(Actor a,Long dishId,MultipartFile file) {
        ops.roles(a,Role.ADMIN);var dish=dishes.findById(dishId).orElseThrow();
        if(!configured) throw new BusinessException("Chưa cấu hình Cloudinary. Điền CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY và CLOUDINARY_API_SECRET rồi khởi động lại.");
        if(file.isEmpty()||file.getSize()>5*1024*1024) throw new BusinessException("Ảnh phải nhỏ hơn hoặc bằng 5 MB.");
        try(var input=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw new BusinessException("Chỉ chấp nhận ảnh JPG, PNG hoặc WebP thật.");
            var reader=readers.next();
            try {
                reader.setInput(input);String fmt=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(!Set.of("png","jpeg","jpg","webp").contains(fmt)||w<1||h<1||w>6000||h>6000||((long)w*h)>16000000)
                    throw new BusinessException("Ảnh JPG/PNG/WebP tối đa 6000 px mỗi chiều và 16 triệu pixel.");
                if(reader.read(0)==null) throw new BusinessException("Nội dung ảnh không hợp lệ.");
            } finally {reader.dispose();}
            var result=client.uploader().upload(file.getBytes(),ObjectUtils.asMap("folder","utetaste/menu","resource_type","image","allowed_formats",new String[]{"jpg","png","webp"},"overwrite",false));
            String url=(String)result.get("secure_url"),publicId=(String)result.get("public_id");
            URI uri=url==null?null:URI.create(url);
            if(uri==null||!"https".equals(uri.getScheme())||!"res.cloudinary.com".equals(uri.getHost())||url.length()>500||publicId==null||publicId.isBlank()||publicId.length()>255)
                throw new BusinessException("Cloudinary trả về thông tin ảnh không hợp lệ. Ảnh cũ được giữ nguyên.");
            // Persist image metadata only: a slow upload must not overwrite concurrent menu edits.
            if(dishes.updateImage(dishId,url,publicId)!=1) throw new BusinessException("Món không còn tồn tại. Chưa cập nhật ảnh.");
            ops.log(a,"UPLOAD_DISH_IMAGE","Món #"+dishId+" • "+dish.getName(),null);
        } catch(IOException | RuntimeException e) {
            if(e instanceof BusinessException b) throw b;
            throw new BusinessException("Không tải được ảnh lên Cloudinary. Ảnh cũ được giữ nguyên; hãy kiểm tra cấu hình và kết nối mạng.");
        }
    }
}
