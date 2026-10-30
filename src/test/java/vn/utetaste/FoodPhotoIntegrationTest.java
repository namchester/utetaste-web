package vn.utetaste;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.security.JwtService;
import vn.utetaste.service.OrderService;
import vn.utetaste.web.Format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:food-photos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class FoodPhotoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Format format;
    @Autowired DishRepository dishes;
    @Autowired AppUserRepository users;
    @Autowired RestaurantTableRepository tables;
    @Autowired JwtService jwt;
    @Autowired OrderService orders;

    @Test
    void existingSvgDatabaseUrlsRenderPhotographsInEveryDishView() throws Exception {
        var dish = dishes.findAll().get(0);
        dish.setImageUrl("/images/chicken.svg");
        dishes.saveAndFlush(dish);
        var admin = users.findByUsername("admin").orElseThrow();
        var actor = new Actor(admin.getId(), admin.getUsername(), admin.getFullName(), Role.ADMIN, null, null);
        var session = orders.openTable(actor, tables.findAll().get(0).getId());
        var access = new Cookie("UT_ACCESS", jwt.staff(admin));
        var guest = new Cookie("UT_GUEST", jwt.guest(session));
        for (String page : new String[]{"/guest/menu", "/admin/menu", "/staff/menu"}) {
            var response = mvc.perform(get(page).cookie(access, guest)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentAsString()).contains("/images/food/chicken.jpg").doesNotContain("/images/chicken.svg");
        }
        // Display mapping must not silently change the original stored URL.
        assertThat(dishes.findById(dish.getId()).orElseThrow().getImageUrl()).isEqualTo("/images/chicken.svg");
    }

    @Test
    void allTenLegacyImagesResolveToPublicDecodablePhotographsWithCredits() throws Exception {
        try (var input = new ClassPathResource("static/images/food/sources.json").getInputStream()) {
            var photos = json.readTree(input).get("photos");
            assertThat(photos.size()).isEqualTo(11);
            int count = 0;
            for (var photo : photos) {
                assertThat(photo.get("author").asText()).isNotBlank();
                assertThat(photo.get("sourcePage").asText()).startsWith("https://commons.wikimedia.org/");
                String url = photo.get("id").asText().equals("interior")
                    ? photo.get("file").asText() : format.image("/images/" + photo.get("id").asText() + ".svg");
                var response = mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(content().contentType("image/jpeg")).andReturn().getResponse();
                var image = ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
                assertThat(image).as(url).isNotNull();
                assertThat(Math.max(image.getWidth(), image.getHeight())).isLessThanOrEqualTo(1280);
                if (!photo.get("id").asText().equals("interior")) count++;
            }
            assertThat(count).isEqualTo(10);
        }
        var credits = mvc.perform(get("/images/food/credits.html")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(credits.getContentAsString()).contains("commons.wikimedia.org", "CC BY");
    }

    @Test
    void uploadedPhotosArePreservedAndEmptyImagesHaveARealPhotoFallback() {
        String cloudinary = "https://res.cloudinary.com/example/image/upload/v1/menu/own-photo.jpg";
        assertThat(format.image(cloudinary)).isEqualTo(cloudinary);
        assertThat(format.image("/images/custom-dish.png")).isEqualTo("/images/custom-dish.png");
        assertThat(format.image("/images/custom-illustration.svg")).isEqualTo("/images/custom-illustration.svg");
        assertThat(format.image(null)).isEqualTo("/images/food/chicken.jpg");
        assertThat(format.image("/images/dish.svg")).isEqualTo("/images/food/chicken.jpg");
    }
}
