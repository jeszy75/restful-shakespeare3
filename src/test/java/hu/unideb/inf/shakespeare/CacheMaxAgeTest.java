package hu.unideb.inf.shakespeare;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** Tests {@code Cache-Control} with a positive {@code shakespeare.cache.max-age}. */
@SpringBootTest(properties = "shakespeare.cache.max-age=1h")
@AutoConfigureMockMvc
class CacheMaxAgeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void cachesMayReuseResponsesForAnHour() throws Exception {
        mvc.perform(get("/plays/macbeth/acts/1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        allOf(containsString("max-age=3600"), containsString("public"))))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED));
    }

}
