package vn.utetaste.config;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CloudinaryConfig {
    @Bean Cloudinary cloudinary(@Value("${app.cloudinary.cloud-name}") String name,
            @Value("${app.cloudinary.api-key}") String key,
            @Value("${app.cloudinary.api-secret}") String secret) {
        return new Cloudinary(ObjectUtils.asMap("cloud_name",name,"api_key",key,
                // cloudinary-http5 2.5 uses seconds for response/connect/request timeout.
                "api_secret",secret,"secure",true,"timeout",20));
    }
}
