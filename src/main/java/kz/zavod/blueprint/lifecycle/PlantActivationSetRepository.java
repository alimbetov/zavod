package kz.zavod.blueprint.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantActivationSetRepository extends JpaRepository<PlantActivationSetEntity,String> {
    long countBySiteId(String siteId);
}
