import Flutter
import UIKit
import Photos
import MobileCoreServices
import CoreLocation
import AVFoundation

public class SwiftRAlbumPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.rhyme_lph/r_album", binaryMessenger: registrar.messenger())
        let instance = SwiftRAlbumPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "createAlbum":
            self.createAlbum(call,result: result)
            break
        case "saveAlbum":
            self.saveAlbum(call,result: result)
            break
        case "saveAlbumWithLocation":
            self.saveAlbumWithLocation(call,result: result)
            break
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func createAlbum(_ call: FlutterMethodCall, result: @escaping FlutterResult){
        let arguments = call.arguments as! Dictionary<String,Any>
        let albumName = arguments["albumName"] as! String
        AlbumSaver(folderName: albumName).createAlbumIfNeeded(completion: result)
    }
    
    private func saveAlbum(_ call: FlutterMethodCall, result: @escaping FlutterResult){
        let arguments = call.arguments as! Dictionary<String,Any>
        let albumName = arguments["albumName"] as! String
        let filePaths = arguments["filePaths"] as! [String]
        
        if filePaths.isEmpty {
            result(true)
            return
        }
        
        let albumSaver = AlbumSaver(folderName: albumName)
        var processedCount = 0
        let totalFiles = filePaths.count
        var hasError = false
        
        for path in filePaths {
            let asset = AVURLAsset.init(url: URL(fileURLWithPath: path), options: nil)
            let videoTracks = asset.tracks(withMediaType: AVMediaType.video)
            let audioTracks = asset.tracks(withMediaType: AVMediaType.audio)
            
            if(videoTracks.count > 0){
                albumSaver.saveVideo(filePath: path) { success in
                    processedCount += 1
                    if !success { hasError = true }
                    if processedCount == totalFiles {
                        result(!hasError)
                    }
                }
            }else if(audioTracks.count > 0){
                // Skip audio files - iOS Photos app doesn't support them
                print("Skipping audio file (not supported in Photos): \(path)")
                processedCount += 1
                if processedCount == totalFiles {
                    result(!hasError)
                }
            }else{
                albumSaver.saveImage(filePath: path) { success in
                    processedCount += 1
                    if !success { hasError = true }
                    if processedCount == totalFiles {
                        result(!hasError)
                    }
                }
            }
        }
    }
    
    private func saveAlbumWithLocation(_ call: FlutterMethodCall, result: @escaping FlutterResult){
        let arguments = call.arguments as! Dictionary<String,Any>
        let albumName = arguments["albumName"] as! String
        let filePaths = arguments["filePaths"] as! [String]
        let latitude = arguments["latitude"] as? Double
        let longitude = arguments["longitude"] as? Double
        
        if filePaths.isEmpty {
            result(true)
            return
        }
        
        let albumSaver = AlbumSaver(folderName: albumName)
        albumSaver.saveFilesWithLocation(filePaths: filePaths, latitude: latitude, longitude: longitude, completion: result)
    }
}

// ref https://stackoverflow.com/questions/28708846/how-to-save-image-to-custom-album
class AlbumSaver: NSObject {
    var albumName: String
    private var assetCollection: PHAssetCollection!
    
    init(folderName: String) {
        self.albumName = folderName
        super.init()
        if let assetCollection = fetchAssetCollectionForAlbum() {
            self.assetCollection = assetCollection
            return
        }
    }
    
    private func checkAuthorizationWithHandler(completion: @escaping ((_ success: Bool) -> Void)) {
        if PHPhotoLibrary.authorizationStatus() == .notDetermined {
            PHPhotoLibrary.requestAuthorization({ (status) in
                self.checkAuthorizationWithHandler(completion: completion)
            })
        }
        else if PHPhotoLibrary.authorizationStatus() == .authorized {
            self.createAlbumIfNeeded { (success) in
                if success {
                    completion(true)
                } else {
                    completion(false)
                }
                
            }
            
        }
        else {
            completion(false)
        }
    }
    
    func createAlbumIfNeeded(completion: @escaping ((_ success: Bool) -> Void)) {
        if let assetCollection = fetchAssetCollectionForAlbum() {
            // Album already exists
            self.assetCollection = assetCollection
            completion(true)
        } else {
            PHPhotoLibrary.shared().performChanges({
                PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: self.albumName)   // create an asset collection with the album name
            }) { success, error in
                if success {
                    self.assetCollection = self.fetchAssetCollectionForAlbum()
                    completion(true)
                } else {
                    // Unable to create album
                    completion(false)
                }
            }
        }
    }
    
    private func fetchAssetCollectionForAlbum() -> PHAssetCollection? {
        let fetchOptions = PHFetchOptions()
        fetchOptions.predicate = NSPredicate(format: "title = %@", self.albumName)
        let collection = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: fetchOptions)
        
        if let _: AnyObject = collection.firstObject {
            return collection.firstObject
        }
        return nil
    }
    
    func saveImage(filePath: String,completion: @escaping ((_ success: Bool) -> Void)) {
        self.checkAuthorizationWithHandler { (success) in

            if success, self.assetCollection != nil {
                PHPhotoLibrary.shared().performChanges({
                    let assetChangeRequest = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: URL(fileURLWithPath: filePath))
                    let assetPlaceHolder = assetChangeRequest?.placeholderForCreatedAsset
                    if let albumChangeRequest = PHAssetCollectionChangeRequest(for: self.assetCollection) {
                        let enumeration: NSArray = [assetPlaceHolder!]
                        albumChangeRequest.addAssets(enumeration)
                    }
                    
                }, completionHandler: { (success, error) in
                    if success {
                        completion(true)
                        print("Successfully saved image to Camera Roll.")
                    } else {
                        completion(false)
                        print("Error writing to image library: \(error!.localizedDescription)")
                    }
                })
            }else{
                completion(false)
                print("Save Image Not Permission.")
            }
        }
    }
    
    func saveVideo(filePath: String, completion: @escaping ((_ success: Bool) -> Void)) {
        self.checkAuthorizationWithHandler { (success) in
            if success, self.assetCollection != nil {
                PHPhotoLibrary.shared().performChanges({
                    let assetChangeRequest = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: URL(fileURLWithPath: filePath))
                    let assetPlaceHolder = assetChangeRequest?.placeholderForCreatedAsset
                    if let albumChangeRequest = PHAssetCollectionChangeRequest(for: self.assetCollection) {
                        let enumeration: NSArray = [assetPlaceHolder!]
                        albumChangeRequest.addAssets(enumeration)
                    }
                    
                }, completionHandler: { (success, error) in
                    if success {
                        completion(true)
                        print("Successfully saved video to Camera Roll.")
                    } else {
                        completion(false)
                        print("Error writing to video library: \(error!.localizedDescription)")
                    }
                })
            }else{
                completion(false)
                print("Save Video Not Permission.")
            }
        }
    }
    
    func saveFilesWithLocation(filePaths: [String], latitude: Double?, longitude: Double?, completion: @escaping ((_ success: Bool) -> Void)) {
        var location: CLLocation? = nil
        if let lat = latitude, let lon = longitude {
            location = CLLocation(latitude: lat, longitude: lon)
        }
        
        var processedCount = 0
        var successCount = 0
        let totalFiles = filePaths.count
        
        for path in filePaths {
            let asset = AVURLAsset.init(url: URL(fileURLWithPath: path), options: nil)
            let videoTracks = asset.tracks(withMediaType: AVMediaType.video)
            let audioTracks = asset.tracks(withMediaType: AVMediaType.audio)
            
            if(videoTracks.count > 0){
                self.saveVideoWithLocation(filePath: path, location: location) { success in
                    processedCount += 1
                    if success {
                        successCount += 1
                    }
                    if processedCount == totalFiles {
                        // Return true only if at least one file was saved successfully
                        completion(successCount > 0)
                    }
                }
            }else if(audioTracks.count > 0){
                // Skip audio files - iOS Photos app doesn't support them
                print("Skipping audio file (not supported in Photos): \(path)")
                processedCount += 1
                if processedCount == totalFiles {
                    // Return true only if at least one file was saved successfully
                    completion(successCount > 0)
                }
            }else{
                self.saveImageWithLocation(filePath: path, location: location) { success in
                    processedCount += 1
                    if success {
                        successCount += 1
                    }
                    if processedCount == totalFiles {
                        // Return true only if at least one file was saved successfully
                        completion(successCount > 0)
                    }
                }
            }
        }
    }
    
    func saveImageWithLocation(filePath: String, location: CLLocation?, completion: @escaping ((_ success: Bool) -> Void)) {
        self.checkAuthorizationWithHandler { (success) in
            if success, self.assetCollection != nil {
                PHPhotoLibrary.shared().performChanges({
                    let assetChangeRequest = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: URL(fileURLWithPath: filePath))
                    
                    // Set location if available
                    if let location = location {
                        assetChangeRequest?.location = location
                    }
                    
                    let assetPlaceHolder = assetChangeRequest?.placeholderForCreatedAsset
                    if let albumChangeRequest = PHAssetCollectionChangeRequest(for: self.assetCollection) {
                        let enumeration: NSArray = [assetPlaceHolder!]
                        albumChangeRequest.addAssets(enumeration)
                    }
                    
                }, completionHandler: { (success, error) in
                    if success {
                        completion(true)
                        print("Successfully saved image with location to Camera Roll.")
                    } else {
                        completion(false)
                        print("Error writing to image library: \(error!.localizedDescription)")
                    }
                })
            }else{
                completion(false)
                print("Save Image Not Permission.")
            }
        }
    }
    
    func saveVideoWithLocation(filePath: String, location: CLLocation?, completion: @escaping ((_ success: Bool) -> Void)) {
        self.checkAuthorizationWithHandler { (success) in
            if success, self.assetCollection != nil {
                PHPhotoLibrary.shared().performChanges({
                    let assetChangeRequest = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: URL(fileURLWithPath: filePath))
                    
                    // Set location if available
                    if let location = location {
                        assetChangeRequest?.location = location
                    }
                    
                    let assetPlaceHolder = assetChangeRequest?.placeholderForCreatedAsset
                    if let albumChangeRequest = PHAssetCollectionChangeRequest(for: self.assetCollection) {
                        let enumeration: NSArray = [assetPlaceHolder!]
                        albumChangeRequest.addAssets(enumeration)
                    }
                    
                }, completionHandler: { (success, error) in
                    if success {
                        completion(true)
                        print("Successfully saved video with location to Camera Roll.")
                    } else {
                        completion(false)
                        print("Error writing to video library: \(error!.localizedDescription)")
                    }
                })
            }else{
                completion(false)
                print("Save Video Not Permission.")
            }
        }
    }
}
