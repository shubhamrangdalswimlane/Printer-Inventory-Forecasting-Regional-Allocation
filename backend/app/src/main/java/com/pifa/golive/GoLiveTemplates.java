package com.pifa.golive;

import com.pifa.common.ApiException;
import com.pifa.ingestion.ExcelFiles;
import com.pifa.master.MasterDataService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Empty Excel templates for the go-live files, with the region columns of the current region master, so the
 * people preparing real data start from exactly the layout the importers expect.
 */
@Component
public class GoLiveTemplates {

    private final MasterDataService master;

    public GoLiveTemplates(MasterDataService master) {
        this.master = master;
    }

    /**
     * @param kind {@code sales} (Product_name, Month, regions...) or {@code allocation} (Product_name, regions...)
     * @return the .xlsx bytes
     * @throws ApiException 404 for any other kind
     */
    public byte[] template(String kind) {
        List<String> regions = master.activeRegionCodes().stream().map(ExcelFiles::capitalise).toList();
        List<String> headers = new ArrayList<>(List.of("Product_name"));
        String sheet;
        switch (kind) {
            case "sales" -> { headers.add("Month"); sheet = "Sales"; }
            case "allocation" -> sheet = "Allocation";
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown template: " + kind);
        }
        headers.addAll(regions);
        return GoLiveFiles.template(sheet, headers);
    }
}
