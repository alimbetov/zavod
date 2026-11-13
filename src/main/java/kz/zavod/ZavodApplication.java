package kz.zavod;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Blueprint engineering foundation; no physical actuator endpoints. */
@SpringBootApplication
public class ZavodApplication {
    public static void main(String[] args) { SpringApplication.run(ZavodApplication.class,args); }
}
