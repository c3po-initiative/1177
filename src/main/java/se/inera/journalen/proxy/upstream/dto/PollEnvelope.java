package se.inera.journalen.proxy.upstream.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Common shape of every "poll" / "detailview" / "polltimeline" response.
 * Fields not present on a given endpoint are simply null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PollEnvelope {

    @JsonProperty("PartialView")
    public String partialView;

    @JsonProperty("TimelineView")
    public String timelineView;

    @JsonProperty("FiltersView")
    public String filtersView;

    @JsonProperty("TotalNumberOfRows")
    public Integer totalNumberOfRows;

    @JsonProperty("PagingEndAtCount")
    public Integer pagingEndAtCount;

    @JsonProperty("ErrorOccurred")
    public Boolean errorOccurred;

    @JsonProperty("MappingErrorOccurred")
    public Boolean mappingErrorOccurred;

    @JsonProperty("LastFetchedDate")
    public String lastFetchedDate;

    /** Whichever HTML fragment is populated. */
    public String htmlBody() {
        if (partialView != null && !partialView.isEmpty()) return partialView;
        if (timelineView != null && !timelineView.isEmpty()) return timelineView;
        return "";
    }
}
