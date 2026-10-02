#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
@interface FileTagsBridge : NSObject
+ (BOOL)writeAtPath:(NSString *)path
            values:(NSDictionary<NSString *, NSString *> *)values
           artwork:(nullable NSData *)artwork
       onlyMissing:(BOOL)onlyMissing
             error:(NSError **)error;
@end
NS_ASSUME_NONNULL_END
