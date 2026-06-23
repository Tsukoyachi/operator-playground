package com.example.operator.crd.dto;

public class ApplicationSpec {
    private String applicationServerImage="ghcr.io/tsukoyachi/operator-playground-app";
    private String applicationServerVersion="";

    public String getApplicationServerImage() {
        return applicationServerImage;
    }

    public void setApplicationServerImage(String applicationServerImage) {
        this.applicationServerImage = applicationServerImage;
    }

    public String getApplicationServerVersion() {
        return applicationServerVersion;
    }

    public void setApplicationServerVersion(String applicationServerVersion) {
        this.applicationServerVersion = applicationServerVersion;
    }
}
