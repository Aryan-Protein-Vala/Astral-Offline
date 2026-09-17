.PHONY: all clean android ios test publish

all: test android ios

test:
	@echo "Running Rust Core Test Suite..."
	cargo test --all -- --nocapture

android:
	@echo "Building Android Libraries and JNI Headers..."
	chmod +x scripts/build-android.sh
	./scripts/build-android.sh

ios:
	@echo "Building iOS Universal Framework..."
	chmod +x scripts/build-ios.sh
	./scripts/build-ios.sh

clean:
	@echo "Cleaning Build Artifacts..."
	cargo clean
	rm -rf target/
	rm -rf ../ios/AstralCoreFramework/
	rm -rf ../android/astralsdk/src/main/jniLibs/

publish:
	@echo "Publishing to Crates.io..."
	cargo publish --dry-run
