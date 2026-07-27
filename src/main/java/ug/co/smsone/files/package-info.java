/**
 * Files module: object storage behind {@link ug.co.smsone.files.FileStorageProvider}. The S3
 * implementation (AWS SDK v2) drives SeaweedFS locally and any S3-compatible store (AWS/R2/B2)
 * in production — switching is pure configuration.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Files")
package ug.co.smsone.files;
