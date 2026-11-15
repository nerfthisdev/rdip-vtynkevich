package com.rbdip.bookstore.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO customers (full_name, address, phone)
            VALUES (:fullName, :address, :phone)
            ON CONFLICT ON CONSTRAINT uk_customers_identity DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("fullName") String fullName, @Param("address") String address,
            @Param("phone") String phone);

    Optional<Customer> findByFullNameAndAddressAndPhone(String fullName, String address, String phone);
}
