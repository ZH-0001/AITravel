package com.aitravel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=","spring.profiles.active=default","spring.datasource.url=jdbc:h2:mem:live_failure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","travel.mode=live","travel.deepseek-key=","travel.search-key=","travel.admin-username=","travel.admin-password="})
class LiveFailureTest {
    @Autowired TaskService tasks;
    @Autowired TravelStore store;
    @Autowired PasswordEncoder encoder;
    @Test void missingLiveCredentialsFailsWithoutCreatingFakePlan() throws Exception {
        long uid=store.insert("INSERT INTO app_user(username,password_hash,nickname,role) VALUES(?,?,?,?)","live_failure",encoder.encode("password-for-tests"),"test","USER");
        var input=new TripInput("上海","杭州",LocalDate.now().plusDays(2),2,1,new BigDecimal("1000"),List.of("自然风光"),"单人",List.of(),"");
        var submitted=tasks.submit(uid,null,new TaskService.Submission(UUID.randomUUID().toString(),input,"",null));
        long id=TravelStore.number(submitted,"task_id");
        for(int i=0;i<100;i++) {
            var result=store.task(uid,id);
            if("FAILED".equals(result.get("status"))) {
                assertThat(result.get("error_code")).isEqualTo("PROVIDER_NOT_CONFIGURED");
                assertThat(store.rows("SELECT * FROM travel_plan WHERE user_id=?",uid)).isEmpty();
                assertThat(store.rows("SELECT * FROM api_log WHERE task_id=?",id)).isEmpty();
                assertThat(result.get("phase_timings")).isNotNull();return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Missing credentials task did not fail");
    }
}
