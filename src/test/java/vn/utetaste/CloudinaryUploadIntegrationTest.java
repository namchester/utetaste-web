package vn.utetaste;

import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import javax.imageio.ImageIO;
import jakarta.servlet.http.Cookie;
import com.cloudinary.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"app.cloudinary.cloud-name=test-cloud","app.cloudinary.api-key=test-key","app.cloudinary.api-secret=test-secret"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
class CloudinaryUploadIntegrationTest {
    @Autowired CloudinaryService service;
    @Autowired DishRepository dishes;
    @Autowired CategoryRepository categories;
    @Autowired AppUserRepository users;
    @Autowired OrderService ops;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @MockitoBean Cloudinary client;
    Uploader uploader;
    Dish dish;
    static final String URL="https://res.cloudinary.com/test-cloud/image/upload/v1/utetaste/menu/test.png";
    Actor actor(String name) {var u=users.findByUsername(name).orElseThrow();return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    Cookie cookie(String name) {return new Cookie("UT_ACCESS",jwt.staff(users.findByUsername(name).orElseThrow()));}
    @BeforeEach void prepare() throws Exception {
        uploader=mock(Uploader.class);when(client.uploader()).thenReturn(uploader);
        when(uploader.upload(any(),anyMap())).thenReturn(Map.of("secure_url",URL,"public_id","utetaste/menu/test"));
        dish=new Dish();dish.setName("Ảnh test");dish.setDescription("Món test");dish.setCategory(categories.findAll().getFirst());dish.setPrice(BigDecimal.valueOf(50000));dish.setImageUrl("/images/food/tea.jpg");dish.setImagePublicId("old/id");dishes.saveAndFlush(dish);
    }
    MockMultipartFile image(String format) throws Exception {
        var out=new ByteArrayOutputStream();assertThat(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),format,out)).isTrue();return new MockMultipartFile("image","photo."+format,"image/"+format,out.toByteArray());
    }
    @Test void jpgAndPngUploadPersistHttpsAndPublicId() throws Exception {
        for(String format:List.of("png","jpeg")) service.upload(actor("admin"),dish.getId(),image(format));
        var saved=dishes.findById(dish.getId()).orElseThrow();assertThat(saved.getImageUrl()).isEqualTo(URL);assertThat(saved.getImagePublicId()).isEqualTo("utetaste/menu/test");
        verify(uploader,times(2)).upload(any(),argThat(options->Boolean.FALSE.equals(options.get("overwrite"))));
    }
    @Test void webpContentIsDecodedBeforeUpload() {
        byte[] webp=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        service.upload(actor("admin"),dish.getId(),new MockMultipartFile("image","photo.webp","image/webp",webp));
        assertThat(dishes.findById(dish.getId()).orElseThrow().getImageUrl()).isEqualTo(URL);
    }
    @Test void forgedMimeEmptyTruncatedAndOversizeAreRejected() throws Exception {
        for(MockMultipartFile file:List.of(new MockMultipartFile("image","fake.png","image/png","<script>bad</script>".getBytes()),
            new MockMultipartFile("image",new byte[0]),new MockMultipartFile("image","huge.png","image/png",new byte[5*1024*1024+1]),
            new MockMultipartFile("image","broken.png","image/png",Arrays.copyOf(image("png").getBytes(),24)))) {
            assertThatThrownBy(()->service.upload(actor("admin"),dish.getId(),file)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(uploader);assertOldImage();
    }
    @Test void dimensionsAndTotalPixelsAreLimited() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(6001,1,BufferedImage.TYPE_INT_RGB),"png",out);
        assertThatThrownBy(()->service.upload(actor("admin"),dish.getId(),new MockMultipartFile("image","wide.png","image/png",out.toByteArray()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(uploader);assertOldImage();
    }
    @Test void cloudIoAndRuntimeFailuresPreserveOldImage() throws Exception {
        when(uploader.upload(any(),anyMap())).thenThrow(new IOException("simulated timeout"));
        assertThatThrownBy(()->service.upload(actor("admin"),dish.getId(),image("png"))).isInstanceOf(BusinessException.class).hasMessageNotContaining("simulated");assertOldImage();
        doThrow(new IllegalStateException("simulated secret detail")).when(uploader).upload(any(),anyMap());
        assertThatThrownBy(()->service.upload(actor("admin"),dish.getId(),image("png"))).isInstanceOf(BusinessException.class).hasMessageNotContaining("secret");assertOldImage();
    }
    @Test void invalidCloudResponsePreservesOldImage() throws Exception {
        when(uploader.upload(any(),anyMap())).thenReturn(Map.of("secure_url","http://res.cloudinary.com/unsafe.png","public_id","test"));
        assertThatThrownBy(()->service.upload(actor("admin"),dish.getId(),image("png"))).isInstanceOf(BusinessException.class);assertOldImage();
    }
    @Test void missingConfigurationDoesNotCallCloudinary() {
        var unconfigured=new CloudinaryService(client,"","","",dishes,ops);
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(()->unconfigured.upload(actor("admin"),dish.getId(),new MockMultipartFile("image",new byte[]{1}))).isInstanceOf(BusinessException.class);verifyNoInteractions(uploader);
    }
    @Test void uploadRequiresAdminAndCsrfAndRendersUpdatedImage() throws Exception {
        String path="/admin/dishes/"+dish.getId()+"/image";
        mvc.perform(multipart(path).file(image("png")).cookie(cookie("waiter")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart(path).file(image("png")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("ordinary").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart(path).file(image("png")).cookie(cookie("admin"))).andExpect(status().isForbidden());
        assertThatThrownBy(()->service.upload(actor("manager"),dish.getId(),image("png"))).isInstanceOf(AccessDeniedException.class);
        mvc.perform(multipart(path).file(image("png")).cookie(cookie("admin")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/menu").param("edit",dish.getId().toString()).cookie(cookie("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(URL))).andExpect(content().string(org.hamcrest.Matchers.containsString("dish-image-preview")));
        var branch=users.findByUsername("waiter").orElseThrow().getBranch();
        var bd=new BranchDish();bd.setBranch(branch);bd.setDish(dish);
        // A saved dish is attached to a branch through the same admin workflow used in production.
        var admin=actor("admin");
        // Reuse the existing admin service to populate branch availability.
        adminService.dish(admin,new AdminService.DishForm(dish.getId(),dish.getName(),dish.getPrice(),dish.getDescription(),dish.getCategory().getId(),true));
        var table=new RestaurantTable();table.setBranch(branch);table.setLabel("Image-"+UUID.randomUUID().toString().substring(0,8));tables.saveAndFlush(table);
        var session=ops.openTable(actor("waiter"),table.getId());
        mvc.perform(get("/guest/menu").cookie(new Cookie("UT_GUEST",jwt.guest(session)))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(URL)));
    }
    @Autowired AdminService adminService;
    @Test void concurrentMenuEditSurvivesSlowUpload() throws Exception {
        var uploading=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var file=image("png");var a=actor("admin");
        when(uploader.upload(any(),anyMap())).thenAnswer(invocation->{uploading.countDown();assertThat(finish.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return Map.of("secure_url",URL,"public_id","utetaste/menu/test");});
        try {
            var future=pool.submit(()->service.upload(a,dish.getId(),file));assertThat(uploading.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            adminService.dish(a,new AdminService.DishForm(dish.getId(),"Tên vừa chỉnh",BigDecimal.valueOf(75000),"Mô tả mới",dish.getCategory().getId(),false));
            finish.countDown();future.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var saved=dishes.findById(dish.getId()).orElseThrow();assertThat(saved.getName()).isEqualTo("Tên vừa chỉnh");assertThat(saved.getPrice()).isEqualByComparingTo("75000");assertThat(saved.isActive()).isFalse();assertThat(saved.getImageUrl()).isEqualTo(URL);
        }finally{finish.countDown();pool.shutdownNow();}
    }
    @Autowired RestaurantTableRepository tables;
    private void assertOldImage() {var current=dishes.findById(dish.getId()).orElseThrow();assertThat(current.getImageUrl()).isEqualTo("/images/food/tea.jpg");assertThat(current.getImagePublicId()).isEqualTo("old/id");}
}
