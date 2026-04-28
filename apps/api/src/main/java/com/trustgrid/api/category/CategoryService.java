package com.trustgrid.api.category;

import com.trustgrid.api.shared.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<CategoryResponse> list() {
        return repository.findEnabled();
    }

    public CategoryResponse get(String categoryCode) {
        return repository.findByCode(categoryCode)
                .orElseThrow(() -> new NotFoundException("Category not found"));
    }
}
