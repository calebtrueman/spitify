#import "FileTagsBridge.h"
#include <taglib/fileref.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>

@implementation FileTagsBridge
+ (BOOL)writeAtPath:(NSString *)path values:(NSDictionary<NSString *, NSString *> *)values artwork:(NSData *)artwork onlyMissing:(BOOL)onlyMissing error:(NSError **)error {
    auto fail = [&](NSString *message) {
        if (error) *error = [NSError errorWithDomain:@"Spitify.FileTags" code:1 userInfo:@{NSLocalizedDescriptionKey: message}];
        return NO;
    };
    try {
        NSMutableDictionary<NSString *, NSString *> *changes = [values mutableCopy];
        NSData *coverData = artwork;
        {
            TagLib::FileRef file(path.fileSystemRepresentation, false);
            if (file.isNull() || !file.file()->isValid()) return fail(@"This file type cannot store these edits.");
            auto properties = file.properties();
            if (onlyMissing) {
                for (NSString *key in values) {
                    auto existing = properties.value(TagLib::String(key.UTF8String));
                    if (!existing.isEmpty() && !existing.front().stripWhiteSpace().isEmpty()) [changes removeObjectForKey:key];
                }
                if (!file.complexProperties("PICTURE").isEmpty()) coverData = nil;
            }
            if (changes.count == 0 && !coverData) return YES;
            for (NSString *key in changes) {
                properties[TagLib::String(key.UTF8String)] = TagLib::StringList(TagLib::String(changes[key].UTF8String, TagLib::String::UTF8));
            }
            const auto rejected = file.setProperties(properties);
            for (NSString *key in changes) {
                if (rejected.contains(TagLib::String(key.UTF8String))) return fail(@"This file type does not support all of these fields.");
            }
            if (coverData) {
                TagLib::List<TagLib::VariantMap> pictures;
                TagLib::VariantMap cover;
                cover.insert("data", TagLib::ByteVector(static_cast<const char *>(coverData.bytes), static_cast<unsigned int>(coverData.length)));
                cover.insert("mimeType", TagLib::String("image/jpeg"));
                cover.insert("pictureType", TagLib::String("Front Cover"));
                cover.insert("description", TagLib::String("Cover"));
                pictures.append(cover);
                // MP4 pictures have no role, so replace its covers instead of appending copies.
                if (![@[@"m4a", @"m4b", @"mp4"] containsObject:path.pathExtension.lowercaseString]) {
                    for (const auto &picture : file.complexProperties("PICTURE")) {
                        if (picture.value("pictureType").toString() != "Front Cover") pictures.append(picture);
                    }
                }
                if (!file.setComplexProperties("PICTURE", pictures)) return fail(@"This file type cannot store cover art.");
            }
            if (!file.save()) return fail(@"Could not save this file's tags.");
        } // Close the writer before opening a fresh reader.
        TagLib::FileRef check(path.fileSystemRepresentation, false);
        if (check.isNull()) return fail(@"The saved file could not be checked. Your original was kept.");
        const auto properties = check.properties();
        for (NSString *key in changes) {
            auto found = properties.value(TagLib::String(key.UTF8String));
            if (found.isEmpty() || found.front() != TagLib::String(changes[key].UTF8String, TagLib::String::UTF8)) {
                return fail([NSString stringWithFormat:@"Could not verify the %@ field. Your original file was kept.", key.lowercaseString]);
            }
        }
        if (coverData) {
            bool found = false;
            TagLib::ByteVector wanted(static_cast<const char *>(coverData.bytes), static_cast<unsigned int>(coverData.length));
            for (const auto &picture : check.complexProperties("PICTURE")) {
                if (picture.value("data").toByteVector() == wanted) found = true;
            }
            if (!found) return fail(@"The saved cover could not be checked. Your original file was kept.");
        }
        return YES;
    } catch (...) { return fail(@"The file could not be updated. Your original was kept."); }
}
@end
