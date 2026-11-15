package com.rbdip.bookstore.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves one customer for an exact set of contact details, including nulls. */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public Customer findOrCreate(String fullName, String address, String phone) {
        // The unique constraint also serializes concurrent inserts of the same customer.
        customerRepository.insertIfAbsent(fullName, address, phone);
        return customerRepository.findByFullNameAndAddressAndPhone(fullName, address, phone)
                .orElseThrow(() -> new IllegalStateException("customer could not be resolved"));
    }
}
