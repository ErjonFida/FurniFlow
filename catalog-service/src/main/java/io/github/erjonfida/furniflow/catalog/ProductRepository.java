package io.github.erjonfida.furniflow.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

interface ProductRepository extends MongoRepository<Product, String> {
}
