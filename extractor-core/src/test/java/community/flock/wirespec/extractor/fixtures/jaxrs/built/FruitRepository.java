package community.flock.wirespec.extractor.fixtures.jaxrs.built;

import java.util.List;

public class FruitRepository {

    public Fruit find(String name) {
        return null;
    }

    public List<Fruit> all() {
        return List.of();
    }

    public boolean remove(String name) {
        return false;
    }
}
